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
	
	@Autowired
	private DataFilterDAO dataFilterDAO;
	
	@Test
	public void execute_shouldSkipIfDisabled() throws Exception {
		AdministrationService adminService = Context.getAdministrationService();
		adminService.setGlobalProperty(ImplConstants.GP_ENTITY_BASIS_MAP_SYNC_ENABLED, "false");
		
		EntityBasisMapSyncTask task = new EntityBasisMapSyncTask();
		task.execute();
		
		// Verify no mappings were created because it was disabled
		Collection<EntityBasisMap> maps = dataFilterDAO.getEntityBasisMapsByBasis(Patient.class.getName(),
		    Location.class.getName(), "1");
		assertTrue(maps.isEmpty());
	}
	
	/**
	 * Happy path: patient 1001 carries a doctorAdminParentLocation person attribute whose value is the
	 * UUID of location 4000. After the task runs, a map row must exist for patient 1001 whose
	 * basis_identifier is the *numeric* location_id ("4000"), not the location UUID. This is the
	 * minimum assertion that proves Blocker 2 is fixed — unit tests alone cannot exercise the Hibernate
	 * filter, but they can prove we wrote the right value.
	 */
	@Test
	public void execute_shouldBackfillPatientToNumericLocationId() throws Exception {
		executeDataSet(SYNC_TASK_TEST_DATA_XML);
		
		AdministrationService adminService = Context.getAdministrationService();
		adminService.setGlobalProperty(ImplConstants.GP_ENTITY_BASIS_MAP_SYNC_ENABLED, "true");
		
		EntityBasisMapSyncTask task = new EntityBasisMapSyncTask();
		task.execute();
		
		Collection<EntityBasisMap> maps = dataFilterDAO.getEntityBasisMaps("1001", Patient.class.getName(),
		    Location.class.getName());
		assertEquals(1, maps.size());
		EntityBasisMap map = maps.iterator().next();
		assertEquals("4000", map.getBasisIdentifier());
	}
	
}
