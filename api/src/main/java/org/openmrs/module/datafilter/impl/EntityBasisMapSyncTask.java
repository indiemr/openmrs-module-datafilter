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

import java.util.Date;
import java.util.List;

import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.AdministrationDAO;
import org.openmrs.module.datafilter.impl.api.db.DataFilterDAO;
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
			
			// Check if the task is enabled
			String enabledStr = adminService.getGlobalProperty(ImplConstants.GP_ENTITY_BASIS_MAP_SYNC_ENABLED, "true");
			if (!"true".equalsIgnoreCase(enabledStr)) {
				log.info("Entity Basis Map sync task is disabled via global property. Exiting.");
				return;
			}
			
			// Read the person attribute type UUID for location resolution
			String attributeTypeUuid = adminService.getGlobalProperty(ImplConstants.GP_LOCATION_ATTRIBUTE_TYPE_UUID,
			    ImplConstants.DEFAULT_LOCATION_ATTRIBUTE_TYPE_UUID);
			
			AdministrationDAO adminDAO = Context.getRegisteredComponent("adminDAO", AdministrationDAO.class);
			DataFilterDAO dataFilterDAO = Context.getRegisteredComponents(DataFilterDAO.class).get(0);
			
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
					// Look up the doctorAdminParentLocation person attribute for this patient and
					// resolve it to the numeric location_id. The attribute stores the location's
					// UUID string, but the entity basis map must hold the numeric location_id so
					// filter lookups (DataFilterSessionContext.getBasisIds()) match.
					String locationQuery = "SELECT l.location_id FROM person_attribute pa "
					        + "JOIN person_attribute_type pat "
					        + "  ON pa.person_attribute_type_id = pat.person_attribute_type_id "
					        + "JOIN location l ON l.uuid = pa.value "
					        + "WHERE pa.person_id = " + patientId + " "
					        + "AND pat.uuid = '" + attributeTypeUuid + "' "
					        + "AND pa.voided = 0";

					List<List<Object>> locRows = adminDAO.executeSQL(locationQuery, true);

					if (!locRows.isEmpty() && !locRows.get(0).isEmpty() && locRows.get(0).get(0) != null) {
						String locationId = locRows.get(0).get(0).toString();
						
						EntityBasisMap map = new EntityBasisMap();
						map.setEntityIdentifier(patientId);
						map.setEntityType(Patient.class.getName());
						map.setBasisIdentifier(locationId);
						map.setBasisType(Location.class.getName());
						map.setCreator(Context.getAuthenticatedUser());
						map.setDateCreated(new Date());
						
						dataFilterDAO.saveEntityBasisMap(map);
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
			
			log.info("Entity Basis Map sync task completed. Backfilled: " + backfilled + ", Skipped (no attribute): "
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
