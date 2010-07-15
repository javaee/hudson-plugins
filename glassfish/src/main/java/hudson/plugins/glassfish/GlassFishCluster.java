/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package hudson.plugins.glassfish;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.io.StringReader;
import hudson.model.BuildListener;
import hudson.tasks.Shell;
import hudson.tasks.BatchFile;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import java.util.Map;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

/**
 * Represents GlassFish Application Server Cluster and it's Instances.
 * @author Harshad Vilekar
 */
@SuppressWarnings("deprecation")
public class GlassFishCluster {

    private AbstractBuild build;
    private Launcher launcher;
    private PrintStream logger;
    BuildListener listener;
    private GlassFishBuilder gfbuilder;
    private int basePort;
    // Number of nodes (hosts) that run the cluster instances.
    int numNodes;
    boolean multiNodeCluster;
    static final int dasAdminPort = 4848, dasHttpPort = 8080, dasNodetNum = 1;
    ArrayList<GlassFishClusterNode> clusterNodes = new ArrayList();
    String clusterName;
    Map<String, GlassFishInstance> clusterMap = new HashMap<String, GlassFishInstance>();
    ArrayList<Node> selectedSlaves;
    String nodeSelectionLabel;
    //GlassFishCluster gfc;

    public GlassFishCluster(AbstractBuild build,
            Launcher launcher,
            PrintStream logger,
            BuildListener listener,
            GlassFishBuilder gfbuilder,
            boolean multiNodeCluster,
            int basePort,
            String clusterName,
            String nodeSelectionLabel) {
        this.build = build;
        this.launcher = launcher;
        this.listener = listener;
        this.logger = logger;
        this.gfbuilder = gfbuilder;
        this.multiNodeCluster = multiNodeCluster;
        this.basePort = basePort;
        this.clusterName = clusterName;
        this.nodeSelectionLabel = nodeSelectionLabel;
    }

    public int getDasAdminPort() {
        return dasAdminPort;
    }

    public String getDasNodeName() {
        //return getNodeName(dasNodetNum);
        return (clusterNodes.get(0).getNodeName());
    }

    public GlassFishClusterNode getDasClusterNode() {
        return clusterNodes.get(0);
    }

    GlassFishClusterNode getClusterNode(int hostNum) {
        if ((hostNum > numNodes) || (hostNum <= 0)) {
            logger.println("ERROR: Invalid Host Number:" + hostNum);
            return null;
        } else {
            return clusterNodes.get(hostNum - 1);
        }
    }

    // auto assign default values
    public void createAutoAssignedClusterMap(String instanceNamePrefix, int numInstances) {
        int base_port = this.basePort;
        for (int i = 1; i <= numInstances; i++) {
            String instanceName = instanceNamePrefix + i;
            GlassFishInstance gfi = new GlassFishInstance(this, instanceName, base_port);
            clusterMap.put(instanceName, gfi);
            //logger.println("Added: " + instanceName + ":" + base_port);
            base_port = base_port + 0x100;
        }
    }

    public void listInstances() {
        for (GlassFishInstance in : clusterMap.values()) {
            logger.println(in.toStr(false));
        }
    }

    // get values from Custom Instance Textbox,
    // form key value pairs of instance name and base port
    // and add those instances to the cluster map.
    public boolean updateClusterMapPerUserPrefs(boolean verbose) {

        boolean retVal = true;
        // load the instance name and value pairs from customInstanceText field
        Properties p = new Properties();

        try {
            p.load(new StringReader(gfbuilder.getCustomInstanceText()));
        } catch (IOException e) {
            logger.println("ERROR Loading customInstanceText: "
                    + gfbuilder.getCustomInstanceText());
            e.printStackTrace();
            return false;
        }

        for (Entry<Object, Object> entry : p.entrySet()) {
            String instance_name;
            int base_port;
            try {
                instance_name = entry.getKey().toString();
                base_port = new Integer(entry.getValue().toString()).intValue();
                GlassFishInstance gfi = new GlassFishInstance(this, instance_name, base_port);
                if (verbose) {
                    if (clusterMap.containsKey(instance_name)) {
                        logger.println("Updated: " + instance_name + ":" + base_port);
                    } else {
                        logger.println("Added: " + instance_name + ":" + base_port);
                    }
                }
                clusterMap.put(instance_name, gfi);

            } catch (NumberFormatException nfe) {
                logger.println("ERROR: Invalid Entry: "
                        + entry.getKey().toString() + " " + entry.getKey().toString());
                retVal = false;
            }
        }
        return retVal;
    }

    // host1 is reserved for DAS
    // instance1..instance(numInstances) are deployed on host1..host(numNodes) in
    // round robbin
    public boolean assignClusterNodesToInstances() {
        int node_num = 0;

        // create a list of Nodes
        ArrayList<Node> slaveNodes = selectSlaveNodesForInstanceDeployment(numNodes, nodeSelectionLabel);
        // we can't continue if there are not enough nodes available for instance deployment
        if (slaveNodes.size() < numNodes) {
            logger.println("ERROR: Not enough nodes available for GlassFish Instance deployment. (Required:"
                    + numNodes + ", Available:" + slaveNodes.size() + ", Label:" + nodeSelectionLabel);
            return false;
        }

        for (Node node : slaveNodes) {
            clusterNodes.add(new GlassFishClusterNode(node, build, logger, listener));
        }

        for (GlassFishInstance in : clusterMap.values()) {
            in.clusterNode = getClusterNode(++node_num);
            in.nodeName = in.clusterNode.getNode().getNodeName();
            node_num = node_num % numNodes;
        }

        return true;
    }

    // install GlassFish on DAS node only
    boolean installGlassFishOnDasNode(String zipBundleURL) {
        if (!installGlassFish(getDasClusterNode(), zipBundleURL)) {
            return false;
        }

        return true;
    }

    // install GlassFish on all (subslave) nodes - except DAS node
    boolean installGlassFishOnNonDasNodes(String zipBundleURL) {
        for (GlassFishClusterNode gfcNode : clusterNodes) {
            if (!gfcNode.getNode().getNodeName().equals(getDasClusterNode().getNode().getNodeName())) {
                if (!installGlassFish(gfcNode, zipBundleURL)) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean installGlassFish(GlassFishClusterNode gfcNode, String zipBundleURL) {

        logger.println(gfcNode.getNode().getNodeName() + ":Installing GlassFish Bundle " + zipBundleURL);
        if (!gfcNode.getInstaller().installGlassFishFromZipBundle(zipBundleURL)) {
            logger.println(gfcNode.getNode().getNodeName() + ": ERROR: GlassFish Installation Failed. ");
            // if the installer fails on one of the nodes, then we can not continue
            // TODO: Cleanup: remove successful and partially completed glassfish installtion
            //       since we are aborting at this point
            return false;
        }

        return true;
    }

    boolean deleteInstall() {
        boolean returnVal = true;
        for (GlassFishClusterNode gfcNode : clusterNodes) {
            logger.println(gfcNode.getNode().getNodeName() + ":Deleting GlassFish Installation ");
            if (!gfcNode.getInstaller().deleteInstall()) {
                logger.println(gfcNode.getNode().getNodeName() + ":ERROR: Couldn't Delete GlassFish Installation ");
                returnVal = false;
            }
        }
        return true;
    }

    public void updateClusterMapPerPortAvailability() {
        for (GlassFishInstance in : clusterMap.values()) {
            in.updatePerPortAvailability();
        }
    }

    /**
     *
     * Create a map of all the instances in the cluster.
     * Override auto assigned ports with user defined - if any.
     * Update port values based  upon which ports are actually free on the system
     */
    boolean createClusterMap(String instanceNamePrefix, int numInstances) {


        createAutoAssignedClusterMap(instanceNamePrefix, numInstances);

        if (!updateClusterMapPerUserPrefs(false)) {
            logger.println("ERROR: Couldn't load customInstanceProperties, Build Aborted!");
            return false;
        }

        // Currently, we use one node per instance
        if (multiNodeCluster) {
            numNodes = clusterMap.size();
        } else {
            numNodes = 1;
        }

        assignClusterNodesToInstances();

        return true;
    }

    ////// Cluster Map initialization. /////////////
    boolean initClusterProperties(String instanceNamePrefix, int numInstances) {

        if (!createClusterMap(instanceNamePrefix, numInstances)) {
            return false;
        }

        updateClusterMapPerPortAvailability();

        if (!createClusterPropsFiles()) {
            return false;
        }
        return true;
    }

    /**
     * Get list of all the Hudson slaves which match the specified label.
     * The label is used to group the nodes for a specific task.
     * For example, label "GFCluster" may indicate GlassFish instances may
     * be deployed on this node.
     * The current node that is running the build is the first element of the list.
     * GlassFish DAS is deployed on it. GlassFish instances are deployed on other nodes.
     * @param label
     * @return
     */
    private ArrayList<Node> getAvailableSlaveNodes(String label) {

        List<Node> allSlaves = Hudson.getInstance().getNodes();
        ArrayList<Node> slaves = new ArrayList<Node>();
        boolean verbose = false;

        // The node that is running on this build.
        // That is the first element of ArrayList. DAS is started on that node.
        // GlassFish Instances are started on the rest of the nodes.
        Node currentNode = Computer.currentComputer().getNode();
        slaves.add(0, currentNode);

        for (Node n : allSlaves) {
            String computerName = n.getNodeName();

            if (n.equals(currentNode)) {
                // this node is already added to the ArrayList at position 0.
                println(verbose, "Current Node " + computerName + " is marked for running GlassFish DAS");
                continue;
            }

            //String thislabel = n.getLabelString();
            Set<Label> labelSet = n.getAssignedLabels();
            boolean labelMatched = false;
            String thislabel = "";
            for (Label l : labelSet) {

                if (l.getName().equalsIgnoreCase(label)) {
                    labelMatched = true;
                    thislabel = l.getName();
                    slaves.add(n);
                    break;
                }
            }
            if (labelMatched) {
                println(verbose, "Node " + computerName + " is available (Label=" + thislabel + ")");
            } else {
                println(verbose, "Node " + computerName + " is ignored (No Label Matched: " + label + ")");

            }

        }
        return slaves;
    }

    void println(boolean verbose, String msg) {
        if (verbose) {
            logger.println(msg);
        }
    }

    /**
     * From the list of available nodes, selects "numNodes" nodes for deploying GlassFish instances.
     * Currently, this simply selects first "numNodes" nodes.
     * First node is always currentComputer that is running the build. So, in case of single node
     * cluster (num_nodes = 1), other slaves are not used
     *
     */
    ArrayList<Node> selectSlaveNodesForInstanceDeployment(int num_nodes, String label) {
        ArrayList<Node> slaves = getAvailableSlaveNodes(label);
        int i = 0;
        ArrayList<Node> selected_slaves = new ArrayList<Node>(num_nodes);
        //TODO: Make the random selection.
        for (Node n : slaves) {
            selected_slaves.add(i++, n);
            if (i >= num_nodes) {
                break;
            }
        }
        return selected_slaves;
    }

    // non standard local contract for some ant scripts
    // this may be removed in future
    public boolean createPropsFileForAntS() {

        String clusterStr =
                "s1as.home=" + clusterNodes.get(0).gfi.GFHOME_DIR + "\n"
                + "cluster.name=" + clusterName + "\n";

        String instanceStr = "";
        for (GlassFishInstance in : clusterMap.values()) {

            if (instanceStr.length() > 0) {
                // add a comma, to seperate from earlier instance
                instanceStr = instanceStr + ",";
            } else {
                // this is beginning of the line
                instanceStr = "instancelist=";
            }

            instanceStr = instanceStr + in.getPropsForAntS();
        }

        return createFile(false, "cluster.properties", clusterStr + instanceStr);
    }

    public boolean createClusterPropsFiles() {

        String clusterStr =
                "cluster.name=" + clusterName
                + "\ncluster.numNodes=" + numNodes
                + "\ncluster.numInstances=" + clusterMap.size()
                + "\ndas.node=" + getDasNodeName()
                + "\ndas.port=" + getDasAdminPort()
                + "\n";

        int i = 0;
        for (GlassFishInstance in : clusterMap.values()) {
            clusterStr = clusterStr + in.getProps(++i);
        }

        if (!createFile(true, "cluster.props", clusterStr)) {
            return false;
        }
        logger.println("=================================================");
        logger.println(clusterStr);
        logger.println("=================================================");

        if (!createPropsFileForAntS()) {
            return false;
        }

        return true;
    }

    public boolean createFile(boolean verbose, String fileName, String fileContents) {

        FilePath projectWorkspace = build.getProject().getWorkspace();
        FilePath propsFile = new FilePath(projectWorkspace, fileName);

        try {
            propsFile.write(fileContents, null);
            if (verbose) {
                logger.println("Created " + propsFile.toURI().toString());
            }

        } catch (IOException e) {
            e.printStackTrace();
            logger.println("IOException !");
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace();
            logger.println("InterruptedException!");
            return false;
        }

        return true;
    }

    // Shell command is executed on the current computer, which is same as DAS Cluster Node.
    boolean execShellCommand(String cmd) {
        PrintStream logger = listener.getLogger();

        try {
            if (getDasClusterNode().getInstaller().isWindows()) {
                BatchFile batch = new BatchFile(cmd);
                logger.println("executing Windows Batch Command");
                if (!batch.perform(build, launcher, listener)) {
                    logger.println("ERROR executing Shell Command:" + cmd);
                    return false;
                }
            } else {
                Shell shell = new Shell(cmd);
                logger.println("executing Shell Command");

                if (!shell.perform(build, launcher, listener)) {
                    logger.println("ERROR executing Shell Command:" + cmd);
                    return false;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            logger.println("ERROR (InterruptedException): executing Shell Command:" + cmd);
            return false;
        }

        return true;
    }
}