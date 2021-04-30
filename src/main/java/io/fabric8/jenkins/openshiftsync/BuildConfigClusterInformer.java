/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.api.model.BuildConfig;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we
 * ensure there is a suitable Jenkins Job object defined with the correct
 * configuration
 */
public class BuildConfigClusterInformer implements ResourceEventHandler<BuildConfig>, Lifecyclable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());
    private SharedIndexInformer<BuildConfig> informer;
    private Set<String> namespaces;

    public BuildConfigClusterInformer(String[] namespaces) {
        this.namespaces = new HashSet<>(Arrays.asList(namespaces));
    }

    public int getListIntervalInSeconds() {
        return 1_000 * GlobalPluginConfiguration.get().getBuildConfigListInterval();
    }

    public void start() {
        LOGGER.info("Starting BuildConfig informer for {} !!" + namespaces);
        LOGGER.debug("listing BuildConfig resources");
        SharedInformerFactory factory = getInformerFactory();
        this.informer = factory.sharedIndexInformerFor(BuildConfig.class, getListIntervalInSeconds());
        informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("BuildConfig informer started for namespace: {}" + namespaces);
        // BuildConfigList list =
        // getOpenshiftClient().buildConfigs().inNamespace(namespace).list();
        // onInit(list.getItems());
    }

    public void stop() {
      LOGGER.info("Stopping informer {} !!" + namespaces);
      if( this.informer != null ) {
        this.informer.stop();
      }
    }


    @Override
    public void onAdd(BuildConfig obj) {
        LOGGER.debug("BuildConfig informer  received add event for: {}" + obj);

        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                String name = metadata.getName();
                LOGGER.info("BuildConfig informer received add event for: {}" + name);
                try {
                    BuildConfigManager.upsertJob(obj);
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onUpdate(BuildConfig oldObj, BuildConfig newObj) {
        LOGGER.debug("BuildConfig informer received update event for: {} to: {}" + oldObj + " " + newObj);
        if (newObj != null) {
            ObjectMeta metadata = oldObj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                String oldRv = oldObj.getMetadata().getResourceVersion();
                String newRv = newObj.getMetadata().getResourceVersion();
                LOGGER.info("BuildConfig informer received update event for: {} to: {}" + oldRv + " " + newRv);
                try {
                    BuildConfigManager.modifyEventToJenkinsJob(newObj);
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onDelete(BuildConfig obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("BuildConfig informer received delete event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String namespace = metadata.getNamespace();
            if (namespaces.contains(namespace)) {
                try {
                    BuildConfigManager.deleteEventToJenkinsJob(obj);
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }

    private void onInit(List<BuildConfig> list) {
        for (BuildConfig buildConfig : list) {
            try {
                BuildConfigManager.upsertJob(buildConfig);
            } catch (Exception e) {
                LOGGER.error("Failed to update job", e);
            }
        }
        // poke the BuildWatcher builds with no BC list and see if we
        // can create job
        // runs for premature builds
        BuildManager.flushBuildsWithNoBCList();
    }

}
