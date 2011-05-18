/**
 *
 * Copyright (C) 2011 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.savvis.vpdc.features;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jclouds.cim.OSType;
import org.jclouds.compute.domain.CIMOperatingSystem;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.domain.Credentials;
import org.jclouds.net.IPSocket;
import org.jclouds.predicates.InetSocketAddressConnect;
import org.jclouds.predicates.RetryablePredicate;
import org.jclouds.savvis.vpdc.domain.Network;
import org.jclouds.savvis.vpdc.domain.Resource;
import org.jclouds.savvis.vpdc.domain.Task;
import org.jclouds.savvis.vpdc.domain.VDC;
import org.jclouds.savvis.vpdc.domain.VM;
import org.jclouds.savvis.vpdc.domain.VMSpec;
import org.jclouds.savvis.vpdc.domain.VM.Status;
import org.jclouds.savvis.vpdc.options.GetVMOptions;
import org.jclouds.savvis.vpdc.reference.VCloudMediaType;
import org.jclouds.ssh.SshClient;
import org.jclouds.util.InetAddresses2;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.net.HostSpecifier;

@Test(groups = "live")
public class VMClientLiveTest extends BaseVPDCClientLiveTest {

   private VMClient client;
   private VM vm;
   private RetryablePredicate<IPSocket> socketTester;

   private String username = checkNotNull(System.getProperty("test." + provider + ".loginUser"), "test." + provider
            + ".loginUser");
   private String password = checkNotNull(System.getProperty("test." + provider + ".loginPassword"), "test." + provider
            + ".loginPassword");

   @Override
   @BeforeGroups(groups = { "live" })
   public void setupClient() {
      super.setupClient();
      client = restContext.getApi().getVMClient();
      socketTester = new RetryablePredicate<IPSocket>(new InetSocketAddressConnect(), 130, 10, TimeUnit.SECONDS);// make
   }

   private String billingSiteId;
   private String vpdcId;

   public void testCreateVirtualMachine() throws Exception {
      billingSiteId = restContext.getApi().getBrowsingClient().getOrg(null).getId();// default
      vpdcId = Iterables.find(restContext.getApi().getBrowsingClient().getOrg(billingSiteId).getVDCs(),
               new Predicate<Resource>() {

                  // try to find the first VDC owned by the current user
                  // check here for what the email property might be, or in
                  // the jclouds-wire.log
                  @Override
                  public boolean apply(Resource arg0) {
                     String description = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId,
                              arg0.getId()).getDescription();
                     return description.indexOf(email) != -1;
                  }

               }).getId();

      String networkTierName = Iterables.get(
               restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId).getAvailableNetworks(), 0)
               .getName();
      String name = prefix;

      // delete any old VM
      VDC vpdc = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId);
      for (Resource resource : vpdc.getResourceEntities()) {
         if (resource.getName().equals(prefix)) {
            taskTester.apply(client.removeVMFromVDC(billingSiteId, vpdcId, resource.getId()).getId());
         }
      }
      CIMOperatingSystem os = Iterables.find(restContext.getApi().listPredefinedOperatingSystems(),
               new Predicate<CIMOperatingSystem>() {

                  @Override
                  public boolean apply(CIMOperatingSystem arg0) {
                     return arg0.getOsType() == OSType.RHEL_64;
                  }

               });
      System.out.printf("vpdcId %s, networkName %s, name %s, os %s%n", vpdcId, networkTierName, name, os);

      // TODO: determine the sizes available in the VDC, for example there's
      // a minimum size of boot disk, and also a preset combination of cpu count vs ram
      Task task = client.addVMIntoVDC(billingSiteId, vpdcId, VMSpec.builder().name(name).networkTierName(
               networkTierName).operatingSystem(os).memoryInGig(2).addDataDrive("/data01", 25).build());

      // make sure there's no error
      assert task.getId() != null && task.getError() == null : task;

      assert this.taskTester.apply(task.getId());
      vm = restContext.getApi().getBrowsingClient().getVMInVDC(billingSiteId, vpdcId, task.getOwner().getId());
      conditionallyCheckSSH();
   }

   public void testCreateMultipleVMs() throws Exception {
      billingSiteId = restContext.getApi().getBrowsingClient().getOrg(null).getId();// default
      vpdcId = Iterables.find(restContext.getApi().getBrowsingClient().getOrg(billingSiteId).getVDCs(),
               new Predicate<Resource>() {

                  // try to find the first VDC owned by the current user
                  // check here for what the email property might be, or in
                  // the jclouds-wire.log
                  @Override
                  public boolean apply(Resource arg0) {
                     String description = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId,
                              arg0.getId()).getDescription();
                     return description.indexOf(email) != -1;
                  }

               }).getId();

      String networkTierName = Iterables.get(
               restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId).getAvailableNetworks(), 0)
               .getId();
      Network networkTier = restContext.getApi().getBrowsingClient().getNetworkInVDC(billingSiteId, vpdcId,
               networkTierName);

      String name = prefix;

      // delete any old VM
      VDC vpdc = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId);
      CIMOperatingSystem os = Iterables.find(restContext.getApi().listPredefinedOperatingSystems(),
               new Predicate<CIMOperatingSystem>() {

                  @Override
                  public boolean apply(CIMOperatingSystem arg0) {
                     return arg0.getOsType() == OSType.RHEL_64;
                  }

               });

      // TODO: Savvis returns network names with a - instead of space on getNetworkInVDC call,
      // fix this once savvis api starts returning correctly
      System.out.printf("vpdcId %s, vpdcName %s, networkName %s, name %s, os %s%n", vpdcId, vpdc.getName(), networkTier
               .getName().replace("-", " "), name, os);

      Builder<VMSpec> vmSpecs = ImmutableSet.<VMSpec> builder();
      int noOfVms = 2;
      for (int i = 0; i < noOfVms; i++) {
         // TODO: determine the sizes available in the VDC, for example there's
         // a minimum size of boot disk, and also a preset combination of cpu count vs ram
         VMSpec vmSpec = VMSpec.builder().name(name + i).operatingSystem(os).memoryInGig(2).networkTierName(
                  networkTierName).addDataDrive("/data01", 25).build();
         vmSpecs.add(vmSpec);
      }

      Set<Task> tasks = client.addMultipleVMsIntoVDC(vpdc.getHref(), vmSpecs.build());

      for (Task task : tasks) {
         // make sure there's no error
         assert task.getId() != null && task.getError() == null : task;

         assert this.taskTester.apply(task.getId());
      }
   }

   public void testCaptureVAppTemplate() throws Exception {
      billingSiteId = restContext.getApi().getBrowsingClient().getOrg(null).getId();// default
      vpdcId = Iterables.find(restContext.getApi().getBrowsingClient().getOrg(billingSiteId).getVDCs(),
               new Predicate<Resource>() {

                  // try to find the first VDC owned by the current user
                  // check here for what the email property might be, or in
                  // the jclouds-wire.log
                  @Override
                  public boolean apply(Resource arg0) {
                     String description = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId,
                              arg0.getId()).getDescription();
                     return description.indexOf(email) != -1;
                  }

               }).getId();

      VDC vpdc = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId);

      for (Resource vApp : Iterables.filter(vpdc.getResourceEntities(), new Predicate<Resource>() {

         @Override
         public boolean apply(Resource arg0) {
            return VCloudMediaType.VAPP_XML.equals(arg0.getType());
         }

      })) {

         System.out.printf("Capturing VAppTemplate for vApp - %s%n", vApp.getName());
         Task task = client.captureVApp(billingSiteId, vpdcId, vApp.getHref());

         // make sure there's no error
         assert task.getId() != null && task.getError() == null : task;

         assert this.taskTester.apply(task.getId());
      }
   }

   public void testCloneVApp() throws Exception {
      billingSiteId = restContext.getApi().getBrowsingClient().getOrg(null).getId();// default
      vpdcId = Iterables.find(restContext.getApi().getBrowsingClient().getOrg(billingSiteId).getVDCs(),
               new Predicate<Resource>() {

                  // try to find the first VDC owned by the current user
                  // check here for what the email property might be, or in
                  // the jclouds-wire.log
                  @Override
                  public boolean apply(Resource arg0) {
                     String description = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId,
                              arg0.getId()).getDescription();
                     return description.indexOf(email) != -1;
                  }

               }).getId();

      VDC vpdc = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId);

      String networkTierName = Iterables.get(vpdc.getAvailableNetworks(), 0).getId();

      for (Resource vApp : Iterables.filter(vpdc.getResourceEntities(), new Predicate<Resource>() {

         @Override
         public boolean apply(Resource arg0) {
            return VCloudMediaType.VAPP_XML.equals(arg0.getType());
         }

      })) {

         System.out.printf("Cloning VApp - %s%n", vApp.getName());

         Task task = client.cloneVApp(vApp.getHref(), "clonedvm", networkTierName);

         // make sure there's no error
         assert task.getId() != null && task.getError() == null : task;

         assert this.taskTester.apply(task.getId());
      }
   }

   private void conditionallyCheckSSH() {
      String ip = Iterables.get(vm.getNetworkConnectionSections(), 0).getIpAddress();
      assert HostSpecifier.isValid(ip);
      if (InetAddresses2.isPrivateIPAddress(ip)) {
         ip = Iterables.get(vm.getNetworkConfigSections(), 0).getInternalToExternalNATRules().get(ip);
      }
      // not sure if the network is public or not, so we have to test
      IPSocket socket = new IPSocket(ip, 22);
      System.err.printf("testing socket %s%n", socket);
      System.err.printf("testing ssh %s%n", socket);
      checkSSH(socket);
   }

   protected void checkSSH(IPSocket socket) {
      socketTester.apply(socket);
      SshClient client = context.utils().sshFactory().create(socket, new Credentials(username, password));
      try {
         client.connect();
         ExecResponse exec = client.exec("echo hello");
         System.out.println(exec);
         assertEquals(exec.getOutput().trim(), "hello");
      } finally {
         if (client != null)
            client.disconnect();
      }
   }

   @Test(enabled = false)
   public void testPowerOffVM() throws Exception {
      billingSiteId = restContext.getApi().getBrowsingClient().getOrg(null).getId();// default
      vpdcId = Iterables.find(restContext.getApi().getBrowsingClient().getOrg(billingSiteId).getVDCs(),
               new Predicate<Resource>() {

                  // try to find the first VDC owned by the current user
                  // check here for what the email property might be, or in
                  // the jclouds-wire.log
                  @Override
                  public boolean apply(Resource arg0) {
                     String description = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId,
                              arg0.getId()).getDescription();
                     return description.indexOf(email) != -1;
                  }

               }).getId();

      VDC vpdc = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId);
      URI vmURI = Iterables.find(vpdc.getResourceEntities(), new Predicate<Resource>() {
         @Override
         public boolean apply(Resource arg0) {
            if (VCloudMediaType.VAPP_XML.equals(arg0.getType())) {
               VM response1 = restContext.getApi().getBrowsingClient().getVM(arg0.getHref(), (GetVMOptions[]) null);
               System.out.printf("powering off vm - %s%n", response1.getName());
               if (response1.getStatus().equals(Status.ON)) {
                  return true;
               }
            }
            return false;
         }

      }).getHref();

      Task task = client.powerOffVM(vmURI);

      // make sure there's no error
      assert task.getId() != null && task.getError() == null : task;

      assert this.taskTester.apply(task.getId());
   }

   @Test(enabled = false)
   public void testPowerOnVM() throws Exception {
      billingSiteId = restContext.getApi().getBrowsingClient().getOrg(null).getId();// default
      vpdcId = Iterables.find(restContext.getApi().getBrowsingClient().getOrg(billingSiteId).getVDCs(),
               new Predicate<Resource>() {

                  // try to find the first VDC owned by the current user
                  // check here for what the email property might be, or in
                  // the jclouds-wire.log
                  @Override
                  public boolean apply(Resource arg0) {
                     String description = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId,
                              arg0.getId()).getDescription();
                     return description.indexOf(email) != -1;
                  }

               }).getId();

      VDC vpdc = restContext.getApi().getBrowsingClient().getVDCInOrg(billingSiteId, vpdcId);
      URI vmURI = Iterables.find(vpdc.getResourceEntities(), new Predicate<Resource>() {
         @Override
         public boolean apply(Resource arg0) {
            if (VCloudMediaType.VAPP_XML.equals(arg0.getType())) {
               VM response1 = restContext.getApi().getBrowsingClient().getVM(arg0.getHref(), (GetVMOptions[]) null);
               System.out.printf("powering on vm - %s%n", response1.getName());
               if (response1.getStatus().equals(Status.OFF)) {
                  return true;
               }
            }
            return false;
         }

      }).getHref();

      Task task = client.powerOnVM(vmURI);

      // make sure there's no error
      assert task.getId() != null && task.getError() == null : task;

      assert this.taskTester.apply(task.getId());
   }

   @AfterGroups(groups = "live")
   protected void tearDown() {
      if (vm != null) {
         assert taskTester.apply(client.removeVMFromVDC(billingSiteId, vpdcId, vm.getId()).getId()) : vm;
      }
      super.tearDown();
   }
}