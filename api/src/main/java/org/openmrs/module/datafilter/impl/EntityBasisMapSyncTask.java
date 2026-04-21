/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.datafilter.impl;

import java.util.List;
import java.util.regex.Pattern;

import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.AdministrationDAO;
import org.openmrs.module.datafilter.impl.api.DataFilterService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled task that backfills the {@code datafilter_entity_basis_map} table for patients who are
 * missing a location mapping. The patient's location is resolved from the
 * {@code doctorAdminParentLocation} person attribute. This task complements the
 * {@code PatientLocationLinkingInterceptor} which only handles newly created patients. This task
 * catches pre-existing patients, bulk imports, or any patients that were missed by the interceptor.
 */
public class EntityBasisMapSyncTask extends AbstractTask {
	
	private static final Logger log = LoggerFactory.getLogger(EntityBasisMapSyncTask.class);
	
	// Whitelist for the attribute type name sourced from a global property. The value is
	// concatenated into a native SQL string (AdministrationDAO.executeSQL has no bind-param
	// overload). Reject anything that is not a plausible attribute type name so a malicious or
	// malformed GP value cannot extend the query.
	private static final Pattern ATTRIBUTE_TYPE_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_ -]{0,49}$");
	
	@Override
	public void execute() {
		if (isExecuting) {
			if (log.isDebugEnabled()) {
				log.debug("Not executing Entity Basis Map sync task (already running)");
			}
			return;
		}
		
		startExecuting();
		try {
			log.info("Starting Entity Basis Map sync task...");
			
			AdministrationService adminService = Context.getAdministrationService();
			
			String enabledStr = adminService.getGlobalProperty(ImplConstants.GP_ENTITY_BASIS_MAP_SYNC_ENABLED, "true");
			if (!"true".equalsIgnoreCase(enabledStr)) {
				log.info("Entity Basis Map sync task is disabled via global property. Exiting.");
				return;
			}
			
			// Look up by attribute name (not UUID) so the task works across environments where
			// Initializer auto-generates a fresh UUID for personAttributeTypes.csv rows with an
			// empty Uuid column.
			String attributeTypeName = adminService.getGlobalProperty(ImplConstants.GP_LOCATION_ATTRIBUTE_TYPE_NAME,
			    ImplConstants.DEFAULT_LOCATION_ATTRIBUTE_TYPE_NAME);
			
			if (attributeTypeName == null || !ATTRIBUTE_TYPE_NAME_PATTERN.matcher(attributeTypeName).matches()) {
				log.error("Entity Basis Map sync task aborted: global property '"
				        + ImplConstants.GP_LOCATION_ATTRIBUTE_TYPE_NAME + "' must match "
				        + ATTRIBUTE_TYPE_NAME_PATTERN.pattern() + " (got: '" + attributeTypeName + "')");
				return;
			}
			
			AdministrationDAO adminDAO = Context.getRegisteredComponent("adminDAO", AdministrationDAO.class);
			DataFilterService dataFilterService = Context.getService(DataFilterService.class);
			
			// Find all non-voided patients who do NOT have a location mapping in the entity basis map
			String unlinkedPatientsQuery = "SELECT p.patient_id FROM patient p " + "WHERE p.voided = 0 "
			        + "AND p.patient_id NOT IN ("
			        + "  SELECT CAST(entity_identifier AS UNSIGNED) FROM datafilter_entity_basis_map "
			        + "  WHERE entity_type = '" + Patient.class.getName() + "'" + "  AND basis_type = '"
			        + Location.class.getName() + "'" + ")";
			
			List<List<Object>> unlinkedRows = adminDAO.executeSQL(unlinkedPatientsQuery, true);
			
			int backfilled = 0;
			int skipped = 0;
			int errors = 0;
			
			for (List<Object> row : unlinkedRows) {
				if (row == null || row.isEmpty() || row.get(0) == null) {
					continue;
				}
				
				String patientId = row.get(0).toString();
				
				try {
					// Resolve the patient's doctorAdminParentLocation attribute value (a location
					// UUID string) to the numeric location_id. The entity basis map stores numeric
					// ids so filter lookups (DataFilterSessionContext.getBasisIds()) match.
					String locationQuery = "SELECT l.location_id FROM person_attribute pa "
					        + "JOIN person_attribute_type pat "
					        + "  ON pa.person_attribute_type_id = pat.person_attribute_type_id "
					        + "JOIN location l ON l.uuid = pa.value " + "WHERE pa.person_id = " + patientId + " "
					        + "AND pat.name = '" + attributeTypeName + "' " + "AND pat.retired = 0 " + "AND pa.voided = 0";
					
					List<List<Object>> locRows = adminDAO.executeSQL(locationQuery, true);
					
					if (!locRows.isEmpty() && !locRows.get(0).isEmpty() && locRows.get(0).get(0) != null) {
						Integer locationId = Integer.valueOf(locRows.get(0).get(0).toString());
						
						// Delegate to the existing @Transactional grantAccess service method — the
						// same path vmed's SubmissionFilter uses on signup. The service opens a
						// Spring transaction per call, which triggers Hibernate's
						// afterTransactionBegin() hook; without that hook the Bahmni event-api
						// HibernateEventInterceptor NPEs on save. grantAccess also performs a
						// hasAccess() idempotency check so re-runs are safe.
						Patient patient = new Patient(Integer.valueOf(patientId));
						Location location = new Location(locationId);
						dataFilterService.grantAccess(patient, location);
						backfilled++;
						
						if (log.isDebugEnabled()) {
							log.debug("Backfilled patient " + patientId + " -> location " + locationId);
						}
					} else {
						skipped++;
						if (log.isDebugEnabled()) {
							log.debug("Skipped patient " + patientId + " (no doctorAdminParentLocation attribute)");
						}
					}
				}
				catch (Exception e) {
					log.error("Failed to backfill patient " + patientId, e);
					errors++;
				}
			}
			
			// Summary is log.warn so it appears in production logs under the default
			// org.openmrs=WARN config without a log-level change. This task runs once nightly,
			// so the noise cost is one line per day — valuable audit trail in exchange.
			log.warn("Entity Basis Map sync task completed. Backfilled: " + backfilled + ", Skipped (no attribute): "
			        + skipped + ", Errors: " + errors);
			
		}
		catch (Exception e) {
			log.error("Error executing Entity Basis Map sync task", e);
		}
		finally {
			stopExecuting();
		}
	}
	
}
