package org.jenkinsci.lib.xtrigger;

import antlr.ANTLRException;
import hudson.FilePath;
import hudson.Util;
import hudson.model.*;
import hudson.triggers.Trigger;
import hudson.util.NullStream;
import hudson.util.StreamTaskListener;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.service.EnvVarsResolver;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Gregory Boissinot
 */
public abstract class AbstractTrigger extends Trigger<BuildableItem> implements Serializable {

    protected static Logger LOGGER = Logger.getLogger(AbstractTrigger.class.getName());

    private String triggerLabel;

    private transient boolean unblockConcurrentBuild;
    protected transient boolean offlineSlaveOnStartup = false;

    public AbstractTrigger(String cronTabSpec) throws ANTLRException {
        super(cronTabSpec);
        this.unblockConcurrentBuild = false;
    }

    protected AbstractTrigger(String cronTabSpec, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec);
        this.unblockConcurrentBuild = unblockConcurrentBuild;
    }

    protected AbstractTrigger(String cronTabSpec, String triggerLabel) throws ANTLRException {
        super(cronTabSpec);
        this.triggerLabel = Util.fixEmpty(triggerLabel);
        this.unblockConcurrentBuild = false;
    }

    protected AbstractTrigger(String cronTabSpec, String triggerLabel, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec);
        this.triggerLabel = Util.fixEmpty(triggerLabel);
        this.unblockConcurrentBuild = unblockConcurrentBuild;
    }

    @SuppressWarnings("unused")
    public String getTriggerLabel() {
        return triggerLabel;
    }

    /**
     * Gets the triggering log file
     *
     * @return the trigger log
     */
    protected abstract File getLogFile();

    /**
     * Checks if a consistency workspace is required for the polling
     *
     * @return true if a workspace is required for the job, false otherwise
     */
    protected abstract boolean requiresWorkspaceForPolling();

    @Override
    public void start(BuildableItem project, boolean newInstance) {
        super.start(project, newInstance);

        XTriggerLog log = new XTriggerLog(new StreamTaskListener(new NullStream()));
        Node pollingNode = getPollingNode(log);
        if (pollingNode == null) {
            log.info("Can't find any complete active node.");
            log.info("Checking again in next polling schedule.");
            log.info("Waiting for next schedule.");
            offlineSlaveOnStartup = true;
            return;
        }

        if (pollingNode.getRootPath() == null) {
            log.info("The running slave might be offline at the moment.");
            log.info("Waiting for next schedule.");
            offlineSlaveOnStartup = true;
            return;
        }

        try {
            start(pollingNode, project, newInstance, log);
        } catch (XTriggerException xe) {
            LOGGER.log(Level.SEVERE, "Can't initialize trigger", xe);
        }
    }

    /**
     * Can be overridden if needed
     */
    protected void start(Node pollingNode, BuildableItem project, boolean newInstance, XTriggerLog log) throws XTriggerException {
    }

    @SuppressWarnings("unused")
    protected String resolveEnvVars(String value, AbstractProject project, Node node) throws XTriggerException {
        EnvVarsResolver varsResolver = new EnvVarsResolver();
        Map<String, String> envVars;
        try {
            envVars = varsResolver.getPollingEnvVars(project, node);
        } catch (EnvInjectException envInjectException) {
            throw new XTriggerException(envInjectException);
        }
        return Util.replaceMacro(value, envVars);
    }

    @Override
    public void run() {
        AbstractProject project = (AbstractProject) job;
        XTriggerDescriptor descriptor = getDescriptor();
        ExecutorService executorService = descriptor.getExecutor();
        XTriggerLog log = null;
        try {
            StreamTaskListener listener = new StreamTaskListener(getLogFile());
            log = new XTriggerLog(listener);
            if (Hudson.getInstance().isQuietingDown()) {
                log.info("Jenkins is quieting down.");
            } else if (!project.isBuildable()) {
                log.info("The job is not buildable. Activate it to poll again.");
            } else if (!unblockConcurrentBuild && project.isBuilding()) {
                log.info("The job is building. Waiting for next poll.");
            } else {
                Runner runner = new Runner(getName());
                executorService.execute(runner);
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Severe error during the trigger execution " + t.getMessage());
            t.printStackTrace();
        } finally {
            if (log != null) {
                log.closeQuietly();
            }
        }
    }

    protected abstract String getName();

    public XTriggerDescriptor getDescriptor() {
        return (XTriggerDescriptor) Hudson.getInstance().getDescriptorOrDie(getClass());
    }

    /**
     * Asynchronous task
     */
    private class Runner implements Runnable, Serializable {

        private String triggerName;

        public Runner(String triggerName) {
            this.triggerName = triggerName;
        }

        @Override
        public void run() {
            XTriggerLog log = null;
            try {

                StreamTaskListener listener = new StreamTaskListener(getLogFile());
                log = new XTriggerLog(listener);

                long start = System.currentTimeMillis();
                log.info("Polling started on " + DateFormat.getDateTimeInstance().format(new Date(start)));
                log.info("Polling for the job " + job.getName());


                Node pollingNode = getPollingNode(log);
                if (pollingNode == null) {
                    log.info("Can't find any complete active node for the polling action.");
                    log.info("Maybe slaves are not yet active at this time or the number of executor of the master is 0.");
                    log.info("Checking again in next polling schedule.");
                    return;
                }

                if (pollingNode.getRootPath() == null) {
                    log.info("The running slave might be offline at the moment.");
                    log.info("Waiting for next schedule.");
                    return;
                }

                displayPollingNode(pollingNode, log);

                //Check if there are modifications in the environment
                boolean changed = checkIfModified(pollingNode, log);
                log.info("\nPolling complete. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start) + ".");

                if (changed) {
                    log.info("Changes found. Scheduling a build.");
                    AbstractProject project = (AbstractProject) job;
                    project.scheduleBuild(0, new XTriggerCause(triggerName, getCause(), true), getScheduledXTriggerActions(pollingNode, log));
                } else {
                    log.info("No changes.");
                }
            } catch (XTriggerException e) {
                reportError(log, e);
            } catch (Throwable e) {
                reportError(log, e);
            } finally {
                if (log != null) {
                    log.closeQuietly();
                }
            }
        }
    }

    private void reportError(XTriggerLog log, Throwable e) {
        log.error("Polling error...");
        String message = e.getMessage();
        if (message != null) {
            log.error("Error message: " + message);
        }
        Throwable cause = e.getCause();
        if (cause != null) {
            log.error("Error cause: " + cause.getMessage());
            cause.printStackTrace();
        }
        e.printStackTrace();
    }

    protected Action[] getScheduledXTriggerActions(Node pollingNode, XTriggerLog log) throws XTriggerException {
        Action[] actions = getScheduledActions(pollingNode, log);
        int nbNewAction = actions.length + 1;
        Action[] newActions = new Action[nbNewAction];
        for (int i = 0; i < actions.length; i++) {
            newActions[i] = actions[i];
        }
        try {
            newActions[newActions.length - 1] = new XTriggerCauseAction(FileUtils.readFileToString(getLogFile()));
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        }
        return newActions;
    }

    protected abstract Action[] getScheduledActions(Node pollingNode, XTriggerLog log);

    /**
     * Checks if there are modifications in the environment between last poll
     *
     * @return true if there are modifications
     */
    protected abstract boolean checkIfModified(Node pollingNode, XTriggerLog log) throws XTriggerException;

    /**
     * Gets the trigger cause
     *
     * @return the trigger cause
     */
    protected abstract String getCause();

    /**
     * Get the node where the polling need to be done
     * <p/>
     * The returned node has a number of executor different of 0.
     *
     * @param log
     * @return the node; null if there is no available node
     */
    private Node getPollingNode(XTriggerLog log) {
        List<Node> nodes = getPollingNodesWithExecutors(log);
        if (nodes == null || nodes.size() == 0) {
            return null;
        }
        //Get the first eligible node
        return nodes.get(0);
    }

    private void displayPollingNode(Node node, XTriggerLog log) {
        assert node != null;
        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.trim().length() == 0) {
            log.info("\nPolling on master.");
        } else {
            log.info("\nPolling remotely on " + nodeName);
        }
    }

    private List<Node> getPollingNodesWithExecutors(XTriggerLog log) {
        List<Node> result = new ArrayList<Node>();
        List<Node> nodes = getPollingNodeList(log);
        for (Node node : nodes) {
            if (node != null && eligibleNode(node)) {
                result.add(node);
            } else if (node != null) {
                log.info(String.format("Finding %s but it is not eligible.", node.getDisplayName()));
            }
        }
        return result;
    }

    private List<Node> getPollingNodeList(XTriggerLog log) {
        log.info("Looking nodes where the poll can be run.");

        List<Node> nodes;
        if (requiresWorkspaceForPolling()) {
            nodes = getPollingNodeListRequiredWS(log);
        } else {
            nodes = getPollingNodeListRequiredNoWS(log);
        }

        if (nodes == null || nodes.size() == 0) {
            log.info("Can't find any eligible slave nodes.");
            log.info("Trying to poll on master node.");
            nodes = Arrays.asList(getMasterNode());
        }

        return nodes;
    }

    private List<Node> getPollingNodeListRequiredNoWS(XTriggerLog log) {

        AbstractProject project = (AbstractProject) job;

        //The specified trigger node must be considered first
        if (triggerLabel != null) {
            log.info(String.format("Looking for a node to the restricted label %s.", triggerLabel));

            if ("master".equalsIgnoreCase(triggerLabel)) {
                log.info("Restrict on master label. Polling on master.");
                return Arrays.asList(getMasterNode());
            }

            Label targetLabel = Hudson.getInstance().getLabel(triggerLabel);
            return getNodesLabel(project, targetLabel);
        }

        return candidatePollingNode(log);
    }

    private List<Node> getPollingNodeListRequiredWS(XTriggerLog log) {

        AbstractProject project = (AbstractProject) job;

        //The specified trigger node must be considered first
        if (triggerLabel != null) {

            log.info(String.format("Looking for a polling node to the restricted label %s.", triggerLabel));

            if ("master".equalsIgnoreCase(triggerLabel)) {
                log.info("Polling on master.");
                return Arrays.asList(getMasterNode());
            }

            Label targetLabel = Hudson.getInstance().getLabel(triggerLabel);
            return getNodesLabel(project, targetLabel);
        }

        //Search for the last built on
        log.info("Looking for the last built on node.");
        Node lastBuildOnNode = project.getLastBuiltOn();
        if (lastBuildOnNode == null) {
            return getPollingNodeNoPreviousBuild(log);
        }
        return Arrays.asList(lastBuildOnNode);
    }

    private boolean eligibleNode(Node node) {
        if (node == null) {
            return false;
        }

        if (node.getRootPath() == null) {
            return false;
        }

        return node.getNumExecutors() != 0;
    }

    private List<Node> getPollingNodeNoPreviousBuild(XTriggerLog log) {
        AbstractProject project = (AbstractProject) job;
        Label targetLabel = getTargetLabel(log);
        if (targetLabel != null) {
            return getNodesLabel(project, targetLabel);
        }
        return null;
    }

    private List<Node> candidatePollingNode(XTriggerLog log) {
        log.info("Looking for a candidate node to run the poll.");
        AbstractProject project = (AbstractProject) job;

        Label targetLabel = getTargetLabel(log);
        if (targetLabel != null) {
            return getNodesLabel(project, targetLabel);
        } else {
            log.info("Looking for a node with no predefined label.");
            log.info("Trying to poll with the last built on node.");
            Node lastBuildOnNode = project.getLastBuiltOn();
            if (lastBuildOnNode == null) {
                log.info("Trying to poll on master node.");
                return Arrays.asList(getMasterNode());
            }
            return Arrays.asList(lastBuildOnNode);
        }
    }

    /**
     * Returns the label if any to poll
     */
    private Label getTargetLabel(XTriggerLog log) {
        AbstractProject p = (AbstractProject) job;
        Label assignedLabel = p.getAssignedLabel();
        if (assignedLabel != null) {
            log.info(String.format("Trying to find an eligible node with the assigned project label %s.", assignedLabel));
            return assignedLabel;
        }

        return null;
    }

    private Node getMasterNode() {
        Computer computer = Hudson.getInstance().toComputer();
        if (computer != null) {
            return computer.getNode();
        } else {
            return null;
        }
    }

    private List<Node> getNodesLabel(AbstractProject project, Label label) {

        Node lastBuildOnNode = project.getLastBuiltOn();
        boolean isAPreviousBuildNode = lastBuildOnNode != null;

        List<Node> result = new ArrayList<Node>();
        Set<Node> nodes = label.getNodes();
        for (Node node : nodes) {
            if (node != null) {
                if (!isAPreviousBuildNode) {
                    FilePath nodePath = node.getRootPath();
                    if (nodePath != null) {
                        result.add(node);
                    }
                } else {
                    FilePath nodeRootPath = node.getRootPath();
                    if (nodeRootPath != null) {
                        if (nodeRootPath.equals(lastBuildOnNode.getRootPath())) {
                            result.add(0, node);
                        }
                    }
                }
            }
        }
        return result;
    }

}
