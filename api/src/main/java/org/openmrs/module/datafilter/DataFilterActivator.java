/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.datafilter;

import org.openmrs.module.BaseModuleActivator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataFilterActivator extends BaseModuleActivator {
	
	private static final Logger log = LoggerFactory.getLogger(DataFilterActivator.class);
	
	/**
	 * @see BaseModuleActivator#started()
	 */
	@Override
	public void started() {
		log.info("Data Filter Module started");
		registerEntityBasisMapSyncTask();
	}
	
	/**
	 * @see BaseModuleActivator#stopped()
	 */
	@Override
	public void stopped() {
		log.info("Data Filter Module stopped");
	}
	
	/**
	 * @see BaseModuleActivator#willStop()
	 */
	@Override
	public void willStop() {
		if (log.isInfoEnabled()) {
			log.info("Removing filter annotations");
		}
		//TODO Remove Annotations
	}
	
	/**
	 * Registers the Entity Basis Map sync task if it doesn't already exist. The task runs nightly at
	 * midnight IST to backfill patients missing from the entity basis map.
	 */
	private void registerEntityBasisMapSyncTask() {
		try {
			org.openmrs.scheduler.SchedulerService schedulerService = org.openmrs.api.context.Context.getSchedulerService();
			String taskName = "Entity Basis Map Sync Task";
			
			if (schedulerService.getTaskByName(taskName) != null) {
				log.info("Entity Basis Map Sync Task already registered, skipping.");
				return;
			}
			
			org.openmrs.scheduler.TaskDefinition task = new org.openmrs.scheduler.TaskDefinition();
			task.setName(taskName);
			task.setDescription("Nightly backfill of patients missing from the entity basis map "
			        + "using non-voided patient_identifier.location_id values.");
			task.setTaskClass(org.openmrs.module.datafilter.impl.EntityBasisMapSyncTask.class.getName());
			task.setRepeatInterval(86400L); // 24 hours
			task.setStartOnStartup(true);
			
			// Schedule first run at midnight IST tonight. Pin the timezone explicitly so the
			// schedule is independent of the container's JVM default timezone (UAT and prod
			// container TZ is not guaranteed to be IST; midnight UTC, for example, is 05:30
			// IST — during morning OPD — which would be the wrong window). The nightly 2 AM IST
			// backup runs 2 hours later and captures the freshly-backfilled state.
			java.util.Calendar midnight = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
			midnight.add(java.util.Calendar.DAY_OF_MONTH, 1);
			midnight.set(java.util.Calendar.HOUR_OF_DAY, 0);
			midnight.set(java.util.Calendar.MINUTE, 0);
			midnight.set(java.util.Calendar.SECOND, 0);
			midnight.set(java.util.Calendar.MILLISECOND, 0);
			task.setStartTime(midnight.getTime());
			
			schedulerService.saveTaskDefinition(task);
			// saveTaskDefinition only persists the row. SchedulerService.onStartup()
			// auto-schedules startOnStartup=true tasks, but it runs before module activators,
			// so a task saved here is not picked up until the next full restart. Schedule it
			// now so the first run happens on the configured first-run date without requiring
			// an extra restart.
			schedulerService.scheduleTask(task);
			log.info("Registered Entity Basis Map Sync Task (first run at midnight IST: " + midnight.getTime() + ")");
		}
		catch (Exception e) {
			log.error("Failed to register Entity Basis Map Sync Task", e);
		}
	}
	
}
