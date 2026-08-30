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
 * Scheduled task that backfills the {@code datafilter_entity_basis_map} table for non-voided
 * patients who do not yet have any location mapping. The patient's location is resolved from their
 * preferred non-voided {@code patient_identifier} (which is the de-facto signal every frontend and
 * BFF read path uses to determine workspace visibility). This task complements the
 * {@code PatientLocationLinkingInterceptor} which only handles patients created in an authenticated
 * session; this task catches pre-existing patients, bulk imports, or any patients the interceptor
 * missed.
 * <h3>Why patient_identifier, not doctorAdminParentLocation</h3> The earlier iteration sourced
 * location from the {@code doctorAdminParentLocation} person attribute. Empirical analysis
 * (2026-04-25) showed that attribute is never read by any frontend or BFF code path; visibility is
 * enforced entirely by {@code patient_identifier.location_id}. The attribute is also missing on ~7%
 * of production patients, which would cause silent invisibility once
 * {@code locationBasedPatientFilter} flips on. Switching the source field is the only behaviour
 * change in this iteration — everything else (skip-already-mapped semantics, the per-patient
 * single-row write, grantAccess delegation) is preserved.
 * <h3>Skipped patients</h3> Patients whose preferred non-voided identifier has no location, or who
 * have no preferred non-voided identifier at all, cannot be mapped automatically. Counted in the
 * summary log line for manual triage.
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
			
			String enabledStr = adminService.getGlobalProperty(ImplConstants.GP_ENTITY_BASIS_MAP_SYNC_ENABLED, "true");
			if (!"true".equalsIgnoreCase(enabledStr)) {
				log.info("Entity Basis Map sync task is disabled via global property. Exiting.");
				return;
			}
			
			AdministrationDAO adminDAO = Context.getRegisteredComponent("adminDAO", AdministrationDAO.class);
			DataFilterService dataFilterService = Context.getService(DataFilterService.class);
			
			// Find non-voided patients with no location mapping in the entity basis map. Patients
			// who already have any basis_map row are left untouched — their existing mapping (from
			// the interceptor at registration time, or from a previous run of this task) is the
			// source of truth. NOT EXISTS with a CAST(int AS CHAR) bridge to the varchar
			// entity_identifier column works on both H2 (test) and MySQL (prod). Schema-only
			// fields are referenced — no user input is interpolated.
			String unlinkedPatientsQuery = "SELECT p.patient_id FROM patient p " + "WHERE p.voided = 0 " + "AND NOT EXISTS ("
			        + "  SELECT 1 FROM datafilter_entity_basis_map m " + "  WHERE m.entity_type = '"
			        + Patient.class.getName() + "' " + "    AND m.basis_type = '" + Location.class.getName() + "' "
			        + "    AND m.entity_identifier = CAST(p.patient_id AS CHAR)" + ")";
			
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
					// Resolve the patient's location from their non-voided patient_identifier
					// rows. Preferred is OpenMRS's canonical "primary" identifier — order by
					// preferred DESC so a preferred row (when present) wins; if none exists,
					// fall back to the earliest non-voided identifier with a location. The
					// ORDER-then-LIMIT shape works whether the column is stored as BOOLEAN
					// (H2 test schema) or TINYINT (MySQL prod schema). Single deterministic
					// row per patient — same "one row per patient" semantics as the prior
					// attribute-based scheduler.
					String locationQuery = "SELECT pi.location_id FROM patient_identifier pi " + "WHERE pi.patient_id = "
					        + patientId + " " + "AND pi.voided = 0 " + "AND pi.location_id IS NOT NULL "
					        + "ORDER BY pi.preferred DESC, pi.patient_identifier_id " + "LIMIT 1";
					
					List<List<Object>> locRows = adminDAO.executeSQL(locationQuery, true);
					
					if (!locRows.isEmpty() && !locRows.get(0).isEmpty() && locRows.get(0).get(0) != null) {
						Integer locationId = Integer.valueOf(locRows.get(0).get(0).toString());
						
						// Delegate to the existing @Transactional grantAccess service method —
						// the same path vmed's SubmissionFilter uses on signup. The service
						// opens a Spring transaction per call which triggers Hibernate's
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
							log.debug("Skipped patient " + patientId + " (no non-voided identifier with location)");
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
			log.warn("Entity Basis Map sync task completed. Backfilled: " + backfilled
			        + ", Skipped (no identifier-location): " + skipped + ", Errors: " + errors);
			
		}
		catch (Exception e) {
			log.error("Error executing Entity Basis Map sync task", e);
		}
		finally {
			stopExecuting();
		}
	}
	
}
