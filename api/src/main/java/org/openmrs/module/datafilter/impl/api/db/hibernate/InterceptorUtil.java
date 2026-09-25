/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.datafilter.impl.api.db.hibernate;

import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;

final class InterceptorUtil {
	
	/**
	 * Gets a GP value from the DB without triggering a hibernate flush.
	 * 
	 * @param gpName the name of the global property
	 * @return the global property value
	 */
	public static String getGpValueNoFlush(String gpName) {
		Session session = Context.getRegisteredComponents(SessionFactory.class).get(0).getCurrentSession();
		//Hibernate will flush any changes in the current session before querying the DB when fetching
		//the GP value below and we end up in this method again, therefore we need to disable auto flush
		final FlushMode flushMode = session.getHibernateFlushMode();
		session.setHibernateFlushMode(FlushMode.MANUAL);
		//Core 2.6.10+/2.7+ requires the Get Global Properties privilege to read a GP (TRUNK-6203), an anonymous
		//request that loads a filtered entity would otherwise fail, proxy the privilege for this read only
		boolean proxied = false;
		if (Context.isSessionOpen()) {
			Context.addProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
			proxied = true;
		}
		try {
			return Context.getAdministrationService().getGlobalProperty(gpName);
		}
		finally {
			if (proxied) {
				Context.removeProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
			}
			//reset
			session.setHibernateFlushMode(flushMode);
		}
	}
	
}
