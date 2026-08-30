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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collection;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.datafilter.TestConstants;
import org.openmrs.module.datafilter.impl.api.db.DataFilterDAO;
import org.springframework.beans.factory.annotation.Autowired;

public class EntityBasisMapSyncTaskTest extends BaseFilterTest {
	
	private static final String SYNC_TASK_TEST_DATA_XML = TestConstants.ROOT_PACKAGE_DIR
	        + "entityBasisMapSyncTaskTestData.xml";
	
	private static final String PATIENT_TYPE = Patient.class.getName();
	
	private static final String LOCATION_TYPE = Location.class.getName();
	
	@Autowired
	private DataFilterDAO dataFilterDAO;
	
	/**
	 * Every test in this class operates on the fresh 2001-2008 cohort. moduleTestData.xml's pre-seeded
	 * basis_map rows for 1001/1002/1003 would otherwise mask whether the task wrote anything.
	 */
	@Before
	public void loadFixture() throws Exception {
		executeDataSet(SYNC_TASK_TEST_DATA_XML);
	}
	
	private void enableTask() {
		Context.getAdministrationService().setGlobalProperty(ImplConstants.GP_ENTITY_BASIS_MAP_SYNC_ENABLED, "true");
	}
	
	private Collection<EntityBasisMap> mapsForPatient(int patientId) {
		return dataFilterDAO.getEntityBasisMaps(String.valueOf(patientId), PATIENT_TYPE, LOCATION_TYPE);
	}
	
	@Test
	public void execute_shouldSkipIfDisabled() throws Exception {
		AdministrationService adminService = Context.getAdministrationService();
		adminService.setGlobalProperty(ImplConstants.GP_ENTITY_BASIS_MAP_SYNC_ENABLED, "false");
		
		new EntityBasisMapSyncTask().execute();
		
		// Patient 2008 has a preferred identifier at 4000 and no pre-seeded basis_map row, so a
		// successful run would produce one. The disabled run must not.
		assertTrue("disabled task must not write any basis_map row", mapsForPatient(2008).isEmpty());
	}
	
	/**
	 * Happy path: the task sources location from the preferred non-voided patient_identifier and writes
	 * the numeric location_id (not the location UUID) into basis_identifier. Patient 2008 has a fresh
	 * single preferred identifier at 4000 — exactly one new basis_map row is expected.
	 */
	@Test
	public void execute_shouldBackfillFromPreferredIdentifierLocation() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		Collection<EntityBasisMap> rows = mapsForPatient(2008);
		assertEquals("happy-path patient must have exactly one new basis_map row", 1, rows.size());
		assertEquals("basis_identifier must be the numeric location_id", "4000",
		    rows.iterator().next().getBasisIdentifier());
	}
	
	/**
	 * A patient with no identifiers carries no location signal — the task must leave them unmapped
	 * rather than inventing one.
	 */
	@Test
	public void execute_shouldSkipPatientWithNoIdentifiers() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		assertTrue("orphan patient (no identifiers) must remain unmapped", mapsForPatient(2001).isEmpty());
	}
	
	/**
	 * A voided identifier represents a withdrawn registration. The owning patient stays unmapped if no
	 * other non-voided preferred identifier exists.
	 */
	@Test
	public void execute_shouldIgnoreVoidedIdentifier() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		assertTrue("voided-identifier-only patient must remain unmapped", mapsForPatient(2002).isEmpty());
	}
	
	/**
	 * An identifier with a NULL location_id offers no usable signal — skip without writing a NULL into
	 * basis_identifier (which is NOT NULL on the basis_map table).
	 */
	@Test
	public void execute_shouldIgnoreIdentifierWithoutLocation() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		assertTrue("no-location-identifier patient must remain unmapped", mapsForPatient(2003).isEmpty());
	}
	
	/**
	 * Voided patients are out of population — even a perfectly valid non-voided preferred identifier on
	 * a voided patient must not produce a basis_map row.
	 */
	@Test
	public void execute_shouldIgnoreVoidedPatient() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		assertTrue("voided patient must not be backfilled", mapsForPatient(2004).isEmpty());
	}
	
	/**
	 * Patient with multiple non-voided identifiers (e.g., simulating a merge survivor): only the
	 * preferred identifier's location is used. Single basis_map row, at the preferred location. Matches
	 * the original "one row per patient" semantics of the attribute-based scheduler.
	 */
	@Test
	public void execute_shouldUsePreferredIdentifierWhenMultipleExist() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		// Patient 2005 in the fixture has preferred=1 at 4000 and preferred=0 at 4001.
		Collection<EntityBasisMap> rows = mapsForPatient(2005);
		assertEquals("only the preferred identifier's location should be written", 1, rows.size());
		assertEquals("4000", rows.iterator().next().getBasisIdentifier());
	}
	
	/**
	 * A patient that already has any basis_map row must not be touched, even if their identifier sits
	 * at a different location. The interceptor (or a previous run) is the source of truth once a row
	 * exists. This preserves the original "leave mapped patients alone" semantic.
	 */
	@Test
	public void execute_shouldNotTouchAlreadyMappedPatient() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		// Patient 2006 in the fixture has a pre-seeded basis_map row at 4001 and an identifier at
		// 4002. The task must leave them alone — no new row at 4002.
		Collection<EntityBasisMap> rows = mapsForPatient(2006);
		assertEquals("already-mapped patient must keep exactly the pre-existing row(s)", 1, rows.size());
		assertEquals("pre-existing basis_identifier must remain", "4001", rows.iterator().next().getBasisIdentifier());
	}
	
	/**
	 * Idempotency on a patient already correctly mapped: re-running must not duplicate the row.
	 */
	@Test
	public void execute_shouldNotDuplicateExistingRow() throws Exception {
		enableTask();
		
		new EntityBasisMapSyncTask().execute();
		
		Collection<EntityBasisMap> rows = mapsForPatient(2007);
		assertEquals("existing basis_map row must not be duplicated", 1, rows.size());
		assertEquals("4000", rows.iterator().next().getBasisIdentifier());
	}
	
	/**
	 * Re-running the task after a successful first run is a no-op — every previously-unmapped patient
	 * is now mapped, and the patient-level NOT IN clause excludes them on the second pass.
	 */
	@Test
	public void execute_shouldBeIdempotentAcrossRuns() throws Exception {
		enableTask();
		EntityBasisMapSyncTask task = new EntityBasisMapSyncTask();
		
		task.execute();
		Collection<EntityBasisMap> rowsAfterFirst = mapsForPatient(2008);
		
		task.execute();
		Collection<EntityBasisMap> rowsAfterSecond = mapsForPatient(2008);
		
		assertEquals("second run must not add a new row for the happy-path patient", rowsAfterFirst.size(),
		    rowsAfterSecond.size());
	}
	
}
