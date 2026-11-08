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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.SessionFactory;
import org.hibernate.criterion.Restrictions;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Location;
import org.openmrs.Order;
import org.openmrs.OrderType;
import org.openmrs.Patient;
import org.openmrs.TestOrder;
import org.openmrs.api.OrderService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.datafilter.TestConstants;
import org.openmrs.module.datafilter.impl.api.DataFilterService;
import org.openmrs.parameter.OrderSearchCriteriaBuilder;
import org.springframework.beans.factory.annotation.Autowired;

public class OrderLocationBasedFilterTest extends BaseFilterTest {
	
	/**
	 * The global property the indiemr-config seed file declares for this filter. Kept as a literal so
	 * that a rename of the constant in code fails a test instead of silently orphaning the seed row, a
	 * missing row means the filter is enforcing.
	 */
	private static final String ORDER_FILTER_DISABLED_GP = "datafilter_locationBasedOrderFilter.disabled";
	
	private static final int DRUG_ORDER_TYPE_ID = 1;
	
	private static final int TEST_ORDER_TYPE_ID = 2;
	
	// patient 1001, location 4000
	private static final Set<Integer> OWN_ORDER_IDS = ids(10001, 10002);
	
	// patients 1001, 1002 and 1003
	private static final Set<Integer> ALL_FIXTURE_ORDER_IDS = ids(10001, 10002, 10003, 10004, 10005, 10006);
	
	private static final String FOREIGN_ORDER_UUID = "9c2b4e6a-1d3f-4a5b-8c7d-0e1f2a310003";
	
	private static final String OWN_ORDER_UUID = "9c2b4e6a-1d3f-4a5b-8c7d-0e1f2a310001";
	
	@Autowired
	private OrderService orderService;
	
	@Autowired
	private PatientService patientService;
	
	@Autowired
	private DataFilterService service;
	
	@Autowired
	private SessionFactory sessionFactory;
	
	private List<OrderType> labsWorklistOrderTypes;
	
	@Before
	public void before() {
		executeDataSet(TestConstants.ROOT_PACKAGE_DIR + "orders.xml");
		labsWorklistOrderTypes = Arrays.asList(orderService.getOrderType(DRUG_ORDER_TYPE_ID),
		    orderService.getOrderType(TEST_ORDER_TYPE_ID));
	}
	
	private static Set<Integer> ids(Integer... ids) {
		return new HashSet<>(Arrays.asList(ids));
	}
	
	private static Set<Integer> orderIds(Collection<? extends Order> orders) {
		return orders.stream().map(Order::getOrderId).collect(Collectors.toSet());
	}
	
	private static Set<Integer> fixtureOrderIds(Collection<? extends Order> orders) {
		return orderIds(orders).stream().filter(ALL_FIXTURE_ORDER_IDS::contains).collect(Collectors.toSet());
	}
	
	/**
	 * The query behind GET /order?orderTypes=... with no patient, which is what the Labs worklist
	 * calls.
	 */
	private List<Order> getLabsWorklistOrders() {
		return orderService.getOrders(new OrderSearchCriteriaBuilder().setOrderTypes(labsWorklistOrderTypes).build());
	}
	
	@SuppressWarnings("unchecked")
	private <T extends Order> List<T> getFixtureOrdersRootedAt(Class<T> orderClass) {
		return sessionFactory.getCurrentSession().createCriteria(orderClass)
		        .add(Restrictions.in("orderId", ALL_FIXTURE_ORDER_IDS)).list();
	}
	
	@Test
	public void getOrders_shouldReturnNoOrdersIfTheUserIsNotGrantedAccessToAnyBasis() {
		reloginAs("dBeckham", "test");
		assertEquals(0, getLabsWorklistOrders().size());
	}
	
	@Test
	public void getOrders_shouldReturnOrdersBelongingToPatientsAccessibleToTheUser() {
		reloginAs("dyorke", "test");
		// No fixture order was placed by dyorke, so these are visible because of the patient's basis
		assertEquals(OWN_ORDER_IDS, orderIds(getLabsWorklistOrders()));
		
		// 4002 is a child of 4001, so patient 1003's orders come in with the grant too
		service.grantAccess(Context.getAuthenticatedUser(), new Location(4001));
		assertEquals(ALL_FIXTURE_ORDER_IDS, orderIds(getLabsWorklistOrders()));
	}
	
	@Test
	public void getOrders_shouldReturnAllOrdersIfTheAuthenticatedUserIsASuperUser() {
		assertTrue(Context.getAuthenticatedUser().isSuperUser());
		assertEquals(ALL_FIXTURE_ORDER_IDS, fixtureOrderIds(getLabsWorklistOrders()));
	}
	
	@Test
	public void getOrders_shouldReturnAllOrdersIfTheOrderFilterIsDisabled() {
		Context.getAdministrationService().setGlobalProperty(ORDER_FILTER_DISABLED_GP, "true");
		// The module reads the .disabled property with plain SQL, so the change must reach the DB first
		Context.flushSession();
		reloginAs("dyorke", "test");
		assertEquals(ALL_FIXTURE_ORDER_IDS, fixtureOrderIds(getLabsWorklistOrders()));
	}
	
	@Test
	public void subclassQueries_shouldBeFilteredByTheFilterOnTheOrderRootClass() {
		// Fixture control: each subclass query sees its 3 rows when unfiltered
		assertEquals(ids(10001, 10003, 10005), orderIds(getFixtureOrdersRootedAt(DrugOrder.class)));
		assertEquals(ids(10002, 10004, 10006), orderIds(getFixtureOrdersRootedAt(TestOrder.class)));
		
		reloginAs("dyorke", "test");
		assertEquals(ids(10001), orderIds(getFixtureOrdersRootedAt(DrugOrder.class)));
		assertEquals(ids(10002), orderIds(getFixtureOrdersRootedAt(TestOrder.class)));
		assertEquals(OWN_ORDER_IDS, orderIds(getFixtureOrdersRootedAt(Order.class)));
	}
	
	@Test
	public void getOrderByUuid_shouldReturnNullForAnOrderOfAPatientTheUserCannotAccess() {
		reloginAs("dyorke", "test");
		assertNull(orderService.getOrderByUuid(FOREIGN_ORDER_UUID));
		assertNotNull(orderService.getOrderByUuid(OWN_ORDER_UUID));
	}
	
	@Test
	public void getActiveOrders_shouldReturnTheActiveOrdersOfAnAccessiblePatient() {
		// The order save path reads the patient's active orders, it must see all of them
		reloginAs("dyorke", "test");
		Patient patient = patientService.getPatient(1001);
		assertEquals(OWN_ORDER_IDS, orderIds(orderService.getActiveOrders(patient, null, null, null)));
	}
	
}
