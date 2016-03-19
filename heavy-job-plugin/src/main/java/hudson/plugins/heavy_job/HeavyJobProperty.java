/*
 * The MIT License
 *
 * Copyright (c) 2010, InfraDNA, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.heavy_job;

import hudson.Extension;
import hudson.model.BuildListener;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue.Executable;
import hudson.model.Queue.Task;
import hudson.model.StringParameterValue;
import hudson.model.queue.AbstractSubTask;
import hudson.model.queue.SubTask;
import hudson.model.queue.WorkUnit;
import hudson.model.queue.WorkUnitContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Keeps track of the number of executors that need to be consumed for this job.
 *
 * @author Kohsuke Kawaguchi
 */
public class HeavyJobProperty extends JobProperty<AbstractProject<?,?>> {
    
    private static final Logger LOGGER = Logger.getLogger(HeavyJobProperty.class.getName());
    
    public final int weight;
    public final boolean sameNode;

    @DataBoundConstructor
    public HeavyJobProperty(int weight, boolean sameNode) {
        this.weight = weight;
        this.sameNode = sameNode;
    }

    private static void logInfo(BuildListener listener, String message) {
        listener.getLogger().println(HeavyJobProperty.class.getSimpleName()+": "+message);
    }

    @Override
    public boolean prebuild(final AbstractBuild<?, ?> build, BuildListener listener) {
        if(this.weight <= 1){
            return true;
        }
        try {
            Executor executor = Executor.currentExecutor();
            WorkUnitContext context = executor.getCurrentWorkUnit().context;
            List<WorkUnit> workUnits = context.getWorkUnits();
            StringBuilder additionalNodes = new StringBuilder();
            Computer _owner = executor.getOwner();
            if(_owner == null){
                logInfo(listener, "executor.getOwner() is null");
                return false;
            }
            String ownerHostname = _owner.getHostName();
            if (ownerHostname == null) {
                _owner.getName();
            }
            logInfo(listener,"owner hostname is "+ownerHostname);
            logInfo(listener,"building additional nodes list...");
            for (Iterator<WorkUnit> i = workUnits.iterator(); i.hasNext();) {
                WorkUnit unit = i.next();
                Computer unitExecutor = unit.getExecutor().getOwner();
                String unitHostname = unitExecutor.getHostName();
                if(unitHostname == null){
                    unitHostname = unitExecutor.getName();
                }
                // exclude the node assigned to the job
                if(unitHostname.equals(ownerHostname)){
                    logInfo(listener,"excluding "+unitHostname+" from the additional nodes");
                    continue;
                }
                logInfo(listener,"adding "+unitHostname+" to the additional nodes list");
                additionalNodes.append(unitHostname);
                if (i.hasNext()) {
                    additionalNodes.append(" ");
                }
            }
            logInfo(listener,"additional nodes list is: "+additionalNodes.toString());
            if(additionalNodes.length() > 0){
                LOGGER.log(Level.INFO, "{0} additional nodes: {1}", new Object[]{build.getFullDisplayName(), additionalNodes});
                List<ParameterValue> params = new ArrayList<>();
                params.add(new StringParameterValue("ADDITIONAL_NODES", additionalNodes.toString()));
                build.addAction(new ParametersAction(params));
            }
            return true;
        } catch (IOException | InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public List<SubTask> getSubTasks() {
        List<SubTask> r = new ArrayList<>();
        for (int i=1; i< weight; i++)
            r.add(new AbstractSubTask() {
                @Override
                public Executable createExecutable() throws IOException {
                    return new ExecutableImpl(this);
                }

                @Override
                public Object getSameNodeConstraint() {
                    // must occupy the same node as the project itself
                    return sameNode ? getProject() : null;
                }

                @Override
                public long getEstimatedDuration() {
                    return getProject().getEstimatedDuration();
                }

                @Override
                public Task getOwnerTask() {
                    return getProject();
                }

                @Override
                public String getDisplayName() {
                    return Messages.HeavyJobProperty_SubTaskDisplayName(getProject().getDisplayName());
                }

                private AbstractProject<?, ?> getProject() {
                    return HeavyJobProperty.this.owner;
                }
            });
        return r;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.HeavyJobProperty_DisplayName();
        }
        
        @Override
        public boolean isApplicable(Class<? extends Job> job) {
            return true;
        }
    }

    public static class ExecutableImpl implements Executable {
        private final SubTask parent;
        private final Executor executor = Executor.currentExecutor();

        private ExecutableImpl(SubTask parent) {
            this.parent = parent;
        }

        @Override
        public SubTask getParent() {
            return parent;
        }

        public AbstractBuild<?,?> getBuild() {
            return (AbstractBuild<?,?>)executor.getCurrentWorkUnit().context.getPrimaryWorkUnit().getExecutable();
        }

        @Override
        public void run() {
            // nothing. we just waste time
        }

        @Override
        public long getEstimatedDuration() {
            return parent.getEstimatedDuration();
        }
    }
}
