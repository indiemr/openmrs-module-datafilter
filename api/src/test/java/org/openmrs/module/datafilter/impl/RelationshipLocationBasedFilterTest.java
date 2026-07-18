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
import org.openmrs.Person;
import org.openmrs.Relationship;
import org.openmrs.api.PersonService;
import org.openmrs.api.context.Context;
import org.openmrs.module.datafilter.TestConstants;
import org.openmrs.module.datafilter.impl.api.DataFilterService;
import org.openmrs.test.TestUtil;
import org.springframework.beans.factory.annotation.Autowired;

public class RelationshipLocationBasedFilterTest extends BaseFilterTest {
	
	@Autowired
	private PersonService personService;
	
	@Autowired
	private DataFilterService service;
	
	@Before
	public void before() {
		executeDataSet(TestConstants.ROOT_PACKAGE_DIR + "otherPersonsThatAreNotPatients.xml");
		executeDataSet(TestConstants.ROOT_PACKAGE_DIR + "relationships.xml");
	}
	
	private Collection<Relationship> getGuardianRelationships() {
		return personService.getRelationshipsByPerson(new Person(500001));
	}
	
	@Test
	public void getRelationshipsByPerson_shouldReturnRelationshipsToPatientsAccessibleToTheUser() {
		reloginAs("dyorke", "test");
		//guardian<->patient1001 (basis location 4000, already accessible to dyorke) +
		//guardian<->guardian (escape, no patient participant); guardian<->patient1002 (location 4001) hidden
		int expCount = 2;
		Collection<Relationship> relationships = getGuardianRelationships();
		assertEquals(expCount, relationships.size());
		assertTrue(TestUtil.containsId(relationships, 3000));
		assertTrue(TestUtil.containsId(relationships, 3002));
		
		service.grantAccess(Context.getAuthenticatedUser(), new Location(4001));
		expCount = 3;
		relationships = getGuardianRelationships();
		assertEquals(expCount, relationships.size());
		assertTrue(TestUtil.containsId(relationships, 3000));
		assertTrue(TestUtil.containsId(relationships, 3001));
		assertTrue(TestUtil.containsId(relationships, 3002));
	}
	
	@Test
	public void getRelationshipsByPerson_shouldReturnOnlyNonPatientRelationshipsIfTheUserIsNotGrantedAccessToAnyBasis() {
		reloginAs("dBeckham", "test");
		Collection<Relationship> relationships = getGuardianRelationships();
		assertEquals(1, relationships.size());
		assertTrue(TestUtil.containsId(relationships, 3002));
	}
	
	@Test
	public void getRelationshipsByPerson_shouldReturnAllRelationshipsIfTheAuthenticatedUserIsASuperUser() {
		assertTrue(Context.getAuthenticatedUser().isSuperUser());
		Collection<Relationship> relationships = getGuardianRelationships();
		assertEquals(3, relationships.size());
		assertTrue(TestUtil.containsId(relationships, 3000));
		assertTrue(TestUtil.containsId(relationships, 3001));
		assertTrue(TestUtil.containsId(relationships, 3002));
	}
	
	@Test
	public void getRelationshipsByPerson_shouldReturnPatientRelationshipsWhereOnlyOneSideIsAccessible() {
		reloginAs("dyorke", "test");
		//patient1001 (basis 4000, accessible) <-> patient1002 (basis 4001, NOT accessible): the row IS
		//returned because one side is accessible — hiding it would hide a patient's own relationships.
		//Accepted residual: the inaccessible counterparty's identity is exposed via association-nav.
		Collection<Relationship> relationships = personService.getRelationshipsByPerson(new Person(1001));
		assertEquals(2, relationships.size());
		assertTrue(TestUtil.containsId(relationships, 3000));
		assertTrue(TestUtil.containsId(relationships, 3003));
		
		//querying by the INACCESSIBLE side still only returns rows that touch an accessible patient:
		//guardian<->patient1002 (3001) stays hidden because neither participant is accessible to dyorke
		relationships = personService.getRelationshipsByPerson(new Person(1002));
		assertEquals(1, relationships.size());
		assertTrue(TestUtil.containsId(relationships, 3003));
	}
	
	@Test
	public void getRelationshipsByPerson_shouldReturnAllRelationshipsIfLocationFilteringIsDisabled() {
		DataFilterTestUtils.disableLocationFiltering();
		reloginAs("dyorke", "test");
		assertEquals(3, getGuardianRelationships().size());
	}
	
}
