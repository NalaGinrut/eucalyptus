/*************************************************************************
 * Copyright 2009-2013 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.loadbalancing.activities;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nullable;
import javax.persistence.EntityTransaction;

import org.apache.log4j.Logger;

import com.eucalyptus.entities.Entities;
import com.eucalyptus.loadbalancing.LoadBalancer;
import com.eucalyptus.loadbalancing.LoadBalancerZone;
import com.eucalyptus.loadbalancing.LoadBalancers;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import edu.ucsb.eucalyptus.msgs.ClusterInfoType;

/**
 * @author Sang-Min Park
 *
 */
public class EventHandlerChainEnableZone extends EventHandlerChain<EnabledZoneEvent> {
	private static Logger LOG  = Logger.getLogger( EventHandlerChainEnableZone.class );
	
	@Override
	public EventHandlerChain<EnabledZoneEvent> build() {
		this.insert(new CheckAndModifyRequest(this));
		this.insert(new CreateOrUpdateAutoscalingGroup(this));
		this.insert(new PersistUpdatedZones(this));
		return this;
	}
	
	private static class CheckAndModifyRequest extends  AbstractEventHandler<EnabledZoneEvent> {
		protected CheckAndModifyRequest(EventHandlerChain<EnabledZoneEvent> chain) {
			super(chain);
		}
	
		@Override
		public void apply(EnabledZoneEvent evt) throws EventHandlerException {
			LoadBalancer lb = null;
			try{
				lb = LoadBalancers.getLoadbalancer(evt.getContext().getUserFullName(), evt.getLoadBalancer());
			}catch(NoSuchElementException ex){
				throw new EventHandlerException("Could not find the loadbalancer with name="+evt.getLoadBalancer(), ex);
			}catch(Exception ex){
				throw new EventHandlerException("Error while looking for loadbalancer with name="+evt.getLoadBalancer(), ex);
			}	
			
			final List<LoadBalancerZone> availableZones = 
					Lists.newArrayList(Collections2.filter(lb.getZones(), new Predicate<LoadBalancerZone>(){
						@Override
						public boolean apply(@Nullable LoadBalancerZone arg0) {
							return arg0.getState().equals(LoadBalancerZone.STATE.InService);
						}
			}));
			
			final List<String> zoneNames = Lists.newArrayList(Collections2.transform(availableZones, 
					new Function<LoadBalancerZone, String>(){
				@Override
				public String apply(@Nullable LoadBalancerZone arg0) {
					return arg0.getName();
				}
			}));
			/// remove the requested zone if it's already part of the loadbalancer
			for(final String avail : zoneNames){
				evt.getZones().remove(avail);
			}
			
			/// make sure the zones are the valid one
			try{
				final List<ClusterInfoType> zones = EucalyptusActivityTasks.getInstance().describeAvailabilityZones();
				final List<String> foundZones = Lists.transform(zones, new Function<ClusterInfoType, String>(){
					@Override
					public String apply(@Nullable ClusterInfoType arg0) {
						return arg0.getZoneName();
					}
				});
				
				for(final String requestedZone : evt.getZones()){
					if(! foundZones.contains(requestedZone)){
						throw new EventHandlerException(String.format("The requested zone %s is not valid", requestedZone));
					}
				}
			}catch (EventHandlerException ex){
				throw ex;
			}catch(Exception ex){
				;
			}
		}

		@Override
		public void rollback() throws EventHandlerException {		
			;
		}	
	}

	private static class CreateOrUpdateAutoscalingGroup extends 
		AbstractEventHandler<EnabledZoneEvent> implements StoredResult<String> {

		protected CreateOrUpdateAutoscalingGroup(
				EventHandlerChain<EnabledZoneEvent> chain) {
			super(chain);
		}

		private String groupName = null;
		private List<String> newZones = null;
		private List<String> oldZones = null;
		private LoadBalancerAutoScalingGroup group = null;
		@Override
		public void apply(EnabledZoneEvent evt) throws EventHandlerException {
			// check if there's the autoscaling group for the loadbalancer
			final List<String> zones = Lists.newArrayList(evt.getZones());
			if(zones==null || zones.size()<=0)
				return;
			
			LoadBalancer lb = null;
			try{
				lb = LoadBalancers.getLoadbalancer(evt.getContext().getUserFullName(), evt.getLoadBalancer());
			}catch(NoSuchElementException ex){
				throw new EventHandlerException("Could not find the loadbalancer with name="+evt.getLoadBalancer(), ex);
			}catch(Exception ex){
				throw new EventHandlerException("Error while looking for loadbalancer with name="+evt.getLoadBalancer(), ex);
			}
			
			group = lb.getAutoScaleGroup();
			// if no autoscale group for the loadbalancer, create one with the specified zone
			// this can happen when the create-load-balancer was called with empty zone
			if(group==null) {
				final EventHandlerChain<NewLoadbalancerEvent> newChain = (new EventHandlerChain<NewLoadbalancerEvent>(){
					@Override
					public EventHandlerChain<NewLoadbalancerEvent> build() {
						this.insert(new EventHandlerChainNew.IAMRoleSetup(this));
						this.insert(new EventHandlerChainNew.InstanceProfileSetup(this));
						this.insert(new EventHandlerChainNew.SecurityGroupSetup(this));
						
						int numVm = 1;
					  	try{
					  		numVm = Integer.parseInt(EventHandlerChainNew.LOADBALANCER_NUM_VM);
					  	}catch(NumberFormatException ex){
					  		LOG.warn("unable to parse loadbalancer_num_vm");
					  	}
					  	this.insert(new LoadBalancerASGroupCreator(this, numVm));
						return this;
					}
				}).build();
				try {
					NewLoadbalancerEvent newEvt = new NewLoadbalancerEvent();
					newEvt.setContext(evt.getContext());
					newEvt.setLoadBalancer(evt.getLoadBalancer());
					newEvt.setZones(evt.getZones());
					newChain.execute(newEvt);
					this.newZones = Lists.newArrayList(evt.getZones());
					this.oldZones = Lists.newArrayList();
				} catch (EventHandlerChainException e) {
					throw new EventHandlerException("failed to create autoscaling group", e);
				}
			}else{
				this.groupName = group.getName();
				// otherwise, update the group if the zone is not there
				final List<String> requestedZones = Lists.newArrayList(evt.getZones());
				final List<LoadBalancerZone> currentZones = 
						Lists.newArrayList(Collections2.filter(lb.getZones(), new Predicate<LoadBalancerZone>(){
							@Override
							public boolean apply(@Nullable LoadBalancerZone arg0) {
								return arg0.getState().equals(LoadBalancerZone.STATE.InService);
							}
				}));
				
				final Set<String> availableZones = Sets.newHashSet(Collections2.transform(currentZones, 
						new Function<LoadBalancerZone, String>(){
					@Override
					public String apply(@Nullable LoadBalancerZone arg0) {
						return arg0.getName();
					}
				}));
				
				final Set<String> newZones = new HashSet<String>(availableZones);
				newZones.addAll(requestedZones);
				if(Sets.difference(newZones, availableZones).size()>0){
					try{
						int capacityPerZone = Integer.parseInt(EventHandlerChainNew.LOADBALANCER_NUM_VM);
						if(capacityPerZone <= 0)
							capacityPerZone = 1;
						final int newCapacity = capacityPerZone * newZones.size();
						
						EucalyptusActivityTasks.getInstance().updateAutoScalingGroup(group.getName(), Lists.newArrayList(newZones), newCapacity);
						final EntityTransaction db = Entities.get( LoadBalancerAutoScalingGroup.class );
						try{
							final LoadBalancerAutoScalingGroup update = Entities.uniqueResult(group);
							update.setCapacity(newCapacity);
							Entities.persist(update);
							db.commit();
						}catch(NoSuchElementException ex){
							db.rollback();
							LOG.error("failed to find the autoscaling group record", ex);
						}catch(Exception ex){
							db.rollback();
							LOG.error("failed to update the autoscaling group record", ex);
						}
						
						this.newZones = Lists.newArrayList(newZones);
						this.oldZones = Lists.newArrayList(availableZones);
					}catch(final Exception ex){
						throw new EventHandlerException("failed to update autoscaling group", ex);
					}
				}
			}
		}

		@Override
		public void rollback() throws EventHandlerException {
			if(this.oldZones != null && this.groupName !=null){
				try{
					int capacityPerZone = Integer.parseInt(EventHandlerChainNew.LOADBALANCER_NUM_VM);
					if(capacityPerZone <= 0)
						capacityPerZone = 1;
					final int oldCapacity = capacityPerZone * this.oldZones.size();
					EucalyptusActivityTasks.getInstance().updateAutoScalingGroup(this.groupName, this.oldZones, oldCapacity);
			
					final EntityTransaction db = Entities.get( LoadBalancerAutoScalingGroup.class );
					try{
						final LoadBalancerAutoScalingGroup update = Entities.uniqueResult(group);
						update.setCapacity(oldCapacity);
						Entities.persist(update);
						db.commit();
					}catch(NoSuchElementException ex){
						db.rollback();
						LOG.error("failed to find the autoscaling group record", ex);
					}catch(Exception ex){
						db.rollback();
						LOG.error("failed to update the autoscaling group record", ex);
					}
				}catch(Exception ex){
					throw new EventHandlerException("failed to update the zone to the original list", ex);
				}
			}
		}

		@Override
		public List<String> getResult() {
			return this.newZones != null ? this.newZones : Lists.<String>newArrayList();
		}		
	}
	
	private static class PersistUpdatedZones extends AbstractEventHandler<EnabledZoneEvent> {
		protected PersistUpdatedZones(EventHandlerChain<EnabledZoneEvent> chain) {
			super(chain);
		}

		private List<LoadBalancerZone> persistedZones = Lists.newArrayList();
		@Override
		public void apply(EnabledZoneEvent evt) throws EventHandlerException {
			LoadBalancer lb = null;
			try{
				lb = LoadBalancers.getLoadbalancer(evt.getContext().getUserFullName(), evt.getLoadBalancer());
			}catch(NoSuchElementException ex){
				throw new EventHandlerException("Could not find the loadbalancer with name="+evt.getLoadBalancer(), ex);
			}catch(Exception ex){
				throw new EventHandlerException("Error while looking for loadbalancer with name="+evt.getLoadBalancer(), ex);
			}
			
			final StoredResult<String> updated = 
					this.getChain().findHandler(CreateOrUpdateAutoscalingGroup.class);
			if(updated!= null && updated.getResult()!= null){
				for(final String zone : updated.getResult()){
					final EntityTransaction db = Entities.get( LoadBalancerZone.class );
					try{
						final LoadBalancerZone exist = 
								Entities.uniqueResult(LoadBalancerZone.named(lb, zone));
						exist.setState(LoadBalancerZone.STATE.InService);
						Entities.persist(exist);
						db.commit();
					}catch(NoSuchElementException ex){
						final LoadBalancerZone newZone = LoadBalancerZone.named(lb, zone);
						newZone.setState(LoadBalancerZone.STATE.InService);
						Entities.persist(newZone);
						db.commit();
						persistedZones.add(newZone);
					}catch(Exception ex){
						db.rollback();
					}
				}
			}
		}

		@Override
		public void rollback() throws EventHandlerException {
			for(final LoadBalancerZone zone : this.persistedZones){
				final EntityTransaction db = Entities.get( LoadBalancerZone.class );
				try{
					final LoadBalancerZone update = Entities.uniqueResult(zone);
					update.setState(LoadBalancerZone.STATE.OutOfService);
					Entities.persist(update);
					db.commit();
				}catch(final Exception ex){
					db.rollback();
					LOG.error("could not mark out of state for the zone", ex);
				}
			}
		}
		
	}
}
