/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009 Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.clustering;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.jboss.as.clustering.jgroups.ChannelFactory;
import org.jboss.logging.Logger;
import org.jboss.util.loading.ContextClassLoaderSwitcher;
import org.jboss.util.loading.ContextClassLoaderSwitcher.SwitchContext;
import org.jboss.util.stream.MarshalledValueInputStream;
import org.jboss.util.stream.MarshalledValueOutputStream;
import org.jgroups.Address;
import org.jgroups.Channel;
import org.jgroups.Event;
import org.jgroups.MembershipListener;
import org.jgroups.MergeView;
import org.jgroups.Message;
import org.jgroups.MessageListener;
import org.jgroups.StateTransferException;
import org.jgroups.Version;
import org.jgroups.View;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.blocks.RspFilter;
import org.jgroups.blocks.mux.MuxRpcDispatcher;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.Buffer;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;

/**
 * Implementation of the {@link GroupCommunicationService} interface and its direct subinterfaces based on a <a
 * href="http://www.jgroups.com/">JGroups</a> <code>MuxRpcDispatcher</code> and a <code>JChannel</code>.
 *
 * TODO: look into decomposing this class: the RPC stuff, the membership stuff and the state transfer stuff could be handled by
 * separate components with this class used to integrate the pieces and expose a common API. The separate components could then
 * be separately testable.
 *
 * @author <a href="mailto:sacha.labourey@cogito-info.ch">Sacha Labourey</a>.
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>.
 * @author Scott.Stark@jboss.org
 * @author Brian Stansberry
 * @author Vladimir Blagojevic
 * @author <a href="mailto:galder.zamarreno@jboss.com">Galder Zamarreno</a>
 * @author Paul Ferraro
 */
public class CoreGroupCommunicationService implements GroupRpcDispatcher, GroupMembershipNotifier, GroupStateTransferService {
    // Constants -----------------------------------------------------

    private static final byte NULL_VALUE = 0;
    private static final byte SERIALIZABLE_VALUE = 1;
    // TODO add Streamable support
    // private static final byte STREAMABLE_VALUE = 2;

    private enum State {
        STOPPED,
        STOPPING,
        STARTING,
        STARTED,
        FAILED,
        DESTROYED,
        CREATED,
        UNREGISTERED,
        ;
        @Override
        public String toString() {
            return this.name().substring(0, 1) + this.name().substring(1).toLowerCase(Locale.US);
        }
    }

    // Constants -----------------------------------------------------

    // Attributes ----------------------------------------------------

    private ChannelFactory channelFactory;
    private String stackName;
    private String groupName;

    private boolean channelSelfConnected;

    /** The JGroups channel */
    Channel channel;
    /** the local JG IP Address */
    private Address localJGAddress = null;
    /** me as a ClusterNode */
    ClusterNode me = null;
    /** The current view of the group */
    private volatile GroupView groupView = new GroupView();

    private long method_call_timeout = 60000;
    Short scopeId;
    private RpcDispatcher dispatcher = null;
    final Map<String, Object> rpcHandlers = new ConcurrentHashMap<String, Object>();
    private boolean directlyInvokeLocal;
    final Map<String, WeakReference<ClassLoader>> clmap = new ConcurrentHashMap<String, WeakReference<ClassLoader>>();

    /** Do we send any membership change notifications synchronously? */
    private boolean allowSyncListeners = false;
    /** The asynchronously invoked GroupMembershipListeners */
    final List<GroupMembershipListener> asyncMembershipListeners = new CopyOnWriteArrayList<GroupMembershipListener>();
    /** The HAMembershipListener and HAMembershipExtendedListeners */
    private final List<GroupMembershipListener> syncMembershipListeners = new CopyOnWriteArrayList<GroupMembershipListener>();
    /** The handler used to send membership change notifications asynchronously */
    private AsynchEventHandler asynchHandler;

    private long state_transfer_timeout = 60000;
    String stateIdPrefix;
    final Map<String, StateTransferProvider> stateProviders = new ConcurrentHashMap<String, StateTransferProvider>();
    final Map<String, StateTransferTask<?, ?>> stateTransferTasks = new ConcurrentHashMap<String, StateTransferTask<?, ?>>();

    @SuppressWarnings("unchecked")
    final ContextClassLoaderSwitcher classLoaderSwitcher = (ContextClassLoaderSwitcher) AccessController.doPrivileged(ContextClassLoaderSwitcher.INSTANTIATOR);

    /** The cluster instance log category */
    protected Logger log = Logger.getLogger(getClass().getName());;
    Logger clusterLifeCycleLog = Logger.getLogger(getClass().getName() + ".lifecycle");
    private final List<String> history = new LinkedList<String>();
    private int maxHistoryLength = 100;

    /** Thread pool used to run state transfer requests */
    Executor threadPool;

    final ThreadGate flushBlockGate = new ThreadGate();

    private final ClusterNodeFactory nodeFactory = new ClusterNodeFactoryImpl();

    final Object channelLock = new Object();

    private State state = State.UNREGISTERED;

    // Static --------------------------------------------------------

    // Constructors --------------------------------------------------

    // GroupCommunicationService implementation ----------------------

    @Override
    public boolean isConsistentWith(GroupCommunicationService other) {
        return this == other;
    }

    @Override
    public String getNodeName() {
        return this.me == null ? null : this.me.getName();
    }

    @Override
    public String getGroupName() {
        return this.groupName;
    }

    public List<String> getCurrentView() {
        GroupView curView = this.groupView;
        List<String> result = new ArrayList<String>(curView.allMembers.size());
        for (ClusterNode member : curView.allMembers) {
            result.add(member.getName());
        }
        return result;
    }

    @Override
    public long getCurrentViewId() {
        return this.groupView.viewId;
    }

    @Override
    public ClusterNode[] getClusterNodes() {
        return this.groupView.allMembers.toArray(new ClusterNode[0]);
    }

    @Override
    public ClusterNode getClusterNode() {
        return this.me;
    }

    public boolean isCurrentNodeCoordinator() {
        GroupView curView = this.groupView;
        if (curView.allMembers.isEmpty() || this.me == null) {
            return false;
        }
        return curView.allMembers.get(0).equals(this.me);
    }

    // ***************************
    // ***************************
    // RPC multicast communication
    // ***************************
    // ***************************

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerRPCHandler(String objName, Object subscriber) {
        this.rpcHandlers.put(objName, subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerRPCHandler(String objName, Object subscriber, ClassLoader classloader) {
        this.registerRPCHandler(objName, subscriber);
        this.clmap.put(objName, new WeakReference<ClassLoader>(classloader));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregisterRPCHandler(String objName, Object subscriber) {
        this.rpcHandlers.remove(objName);
        this.clmap.remove(objName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> List<T> callMethodOnCluster(String serviceName, String methodName, Object[] args, Class<?>[] types, boolean excludeSelf) throws InterruptedException {
        return this.callMethodOnCluster(serviceName, methodName, args, types, excludeSelf, null, this.getMethodCallTimeout(), false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> List<T> callMethodOnCluster(String serviceName, String methodName, Object[] args, Class<?>[] types, boolean excludeSelf, ResponseFilter filter) throws InterruptedException {
        return this.callMethodOnCluster(serviceName, methodName, args, types, excludeSelf, filter, this.getMethodCallTimeout(), false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> List<T> callMethodOnCluster(String serviceName, String methodName, Object[] args, Class<?>[] types, boolean excludeSelf, ResponseFilter filter, long methodTimeout, boolean unordered) throws InterruptedException {
        MethodCall m = new MethodCall(serviceName + "." + methodName, args, types);
        RequestOptions options = new RequestOptions(ResponseMode.GET_ALL, methodTimeout, false, new NoHandlerForRPCRspFilter(filter));
        if (excludeSelf) {
            options.setExclusionList(this.localJGAddress);
        }

        if (this.channel.flushSupported()) {
            this.flushBlockGate.await(this.getMethodCallTimeout());
        }

        boolean trace = this.log.isTraceEnabled();
        if (trace) {
            this.log.tracef("Calling synchronous method on cluster, serviceName=%s, methodName=%s, members=%s, excludeSelf=%s", serviceName, methodName, this.groupView, excludeSelf);
        }
        try {
            RspList<T> rsp = this.dispatcher.callRemoteMethods(null, m, options);
            List<T> result = this.processResponseList(rsp, trace);

            if (!excludeSelf && this.directlyInvokeLocal && (filter == null || filter.needMoreResponses())) {
                invokeDirectly(serviceName, methodName, args, types, result, filter);
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    <T> T invokeDirectly(String serviceName, String methodName, Object[] args, Class<?>[] types, List<T> remoteResponses, ResponseFilter filter) throws Exception {
        T retVal = null;
        Object handler = this.rpcHandlers.get(serviceName);
        if (handler != null) {
            MethodCall call = new MethodCall(methodName, args, types);
            try {
                Object result = call.invoke(handler);
                retVal = (T) result;
                if (remoteResponses != null && (filter == null || filter.isAcceptable(retVal, me))) {
                    remoteResponses.add(retVal);
                }
            } catch (Exception e) {
                throw e;
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        return retVal;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T callMethodOnCoordinatorNode(String serviceName, String methodName, Object[] args, Class<?>[] types, boolean excludeSelf) throws Exception {
        return this.callMethodOnCoordinatorNode(serviceName, methodName, args, types, excludeSelf, this.getMethodCallTimeout(), false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T callMethodOnCoordinatorNode(String serviceName, String methodName, Object[] args, Class<?>[] types, boolean excludeSelf, long methodTimeout, boolean unordered) throws Exception {
        boolean trace = this.log.isTraceEnabled();

        MethodCall m = new MethodCall(serviceName + "." + methodName, args, types);

        if (trace) {
            this.log.tracef("callMethodOnCoordinatorNode(false), objName=%s, methodName=", serviceName, methodName);
        }

        // the first cluster view member is the coordinator
        // If we are the coordinator, only call ourself if 'excludeSelf' is false
        if (this.isCurrentNodeCoordinator()) {
            if (excludeSelf) {
                return null;
            } else if (this.directlyInvokeLocal) {
                return invokeDirectly(serviceName, methodName, args, types, null, null);
            }
        }

        Address coord = this.groupView.coordinator;
        RequestOptions opt = new RequestOptions(ResponseMode.GET_ALL, methodTimeout, false, new NoHandlerForRPCRspFilter());
        if (unordered) {
            opt.setFlags(Message.OOB);
        }
        try {
            return this.dispatcher.callRemoteMethod(coord, m, opt);
        } catch (Exception e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Caught raw Throwable on remote invocation", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T>  T callMethodOnNode(String serviceName, String methodName, Object[] args, Class<?>[] types,
            ClusterNode targetNode) throws Exception {
        return this.callMethodOnNode(serviceName, methodName, args, types, this.getMethodCallTimeout(), targetNode, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T callMethodOnNode(String serviceName, String methodName, Object[] args, Class<?>[] types, long methodTimeout, ClusterNode targetNode) throws Exception {
        return this.callMethodOnNode(serviceName, methodName, args, types, methodTimeout, targetNode, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T callMethodOnNode(String serviceName, String methodName, Object[] args, Class<?>[] types, long methodTimeout, ClusterNode targetNode, boolean unordered) throws Exception {
        if (!(targetNode instanceof ClusterNodeImpl)) {
            throw new IllegalArgumentException(String.format("targetNode %s is not an instance of -- only targetNodes provided by this HAPartition should be used", targetNode, ClusterNodeImpl.class.getName()));
        }
        boolean trace = this.log.isTraceEnabled();

        MethodCall m = new MethodCall(serviceName + "." + methodName, args, types);

        if (trace) {
            this.log.tracef("callMethodOnNode( objName=%s, methodName=%s", serviceName, methodName);
        }
        if (this.directlyInvokeLocal && this.me.equals(targetNode)) {
            return invokeDirectly(serviceName, methodName, args, types, null, null);
        }

        RequestOptions opt = new RequestOptions(ResponseMode.GET_FIRST, methodTimeout, false, new NoHandlerForRPCRspFilter());
        if (unordered) {
            opt.setFlags(Message.OOB);
        }
        try {
            return this.dispatcher.callRemoteMethod(((ClusterNodeImpl) targetNode).getOriginalJGAddress(), m, opt);
        } catch (Exception e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Caught raw Throwable on remote invocation", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void callAsyncMethodOnNode(String serviceName, String methodName, Object[] args, Class<?>[] types,
            ClusterNode targetNode) throws Exception {
        this.callAsyncMethodOnNode(serviceName, methodName, args, types, targetNode, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void callAsyncMethodOnNode(String serviceName, String methodName, Object[] args, Class<?>[] types, ClusterNode targetNode, boolean unordered) throws Exception {

        if (!(targetNode instanceof ClusterNodeImpl)) {
            throw new IllegalArgumentException(String.format("targetNode is not an instance of -- only targetNodes provided by this HAPartition should be used", targetNode, ClusterNodeImpl.class.getName()));
        }
        boolean trace = this.log.isTraceEnabled();

        MethodCall m = new MethodCall(serviceName + "." + methodName, args, types);

        if (trace) {
            this.log.tracef("callAsyncMethodOnNode( objName=%s, methodName=%s", serviceName, methodName);
        }

        if (this.directlyInvokeLocal && this.me.equals(targetNode)) {
            new AsynchronousLocalInvocation(serviceName, methodName, args, types).invoke();
            return;
        }

        RequestOptions opt = new RequestOptions(ResponseMode.GET_NONE, this.getMethodCallTimeout(), false, new NoHandlerForRPCRspFilter());
        if (unordered) {
            opt.setFlags(Message.OOB);
        }
        try {
            this.dispatcher.callRemoteMethod(((ClusterNodeImpl) targetNode).getOriginalJGAddress(), m, opt);
        } catch (Exception e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Caught raw Throwable on remote invocation", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void callAsynchMethodOnCluster(String serviceName, String methodName, Object[] args, Class<?>[] types, boolean excludeSelf) throws InterruptedException {
        this.callAsynchMethodOnCluster(serviceName, methodName, args, types, excludeSelf, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void callAsynchMethodOnCluster(final String serviceName, final String methodName, final Object[] args, final Class<?>[] types, boolean excludeSelf, boolean unordered) throws InterruptedException {
        MethodCall m = new MethodCall(serviceName + "." + methodName, args, types);
        RequestOptions options = new RequestOptions(ResponseMode.GET_NONE, this.getMethodCallTimeout(), false, new NoHandlerForRPCRspFilter());
        if (excludeSelf) {
            options.setExclusionList(this.localJGAddress);
        }

        if (this.channel.flushSupported()) {
            this.flushBlockGate.await(this.getMethodCallTimeout());
        }
        if (this.log.isTraceEnabled()) {
            this.log.trace("calling asynch method on cluster, serviceName=" + serviceName + ", methodName=" + methodName
                    + ", members=" + this.groupView + ", excludeSelf=" + excludeSelf);
        }
        try {
            this.dispatcher.callRemoteMethods(null, m, options);
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (!excludeSelf && this.directlyInvokeLocal) {
                new AsynchronousLocalInvocation(serviceName, methodName, args, types).invoke();
            }
        }

    }

    @Override
    public void callAsyncMethodOnCoordinatorNode(String serviceName, String methodName, Object[] args, Class<?>[] types,
            boolean excludeSelf) throws Exception {
        this.callAsyncMethodOnCoordinatorNode(serviceName, methodName, args, types, excludeSelf, false);
    }

    @Override
    public void callAsyncMethodOnCoordinatorNode(String serviceName, String methodName, Object[] args, Class<?>[] types,
            boolean excludeSelf, boolean unordered) throws Exception {

        boolean trace = this.log.isTraceEnabled();

        MethodCall m = new MethodCall(serviceName + "." + methodName, args, types);

        if (trace) {
            this.log.trace("callMethodOnCoordinatorNode(false), objName=" + serviceName + ", methodName=" + methodName);
        }

        // the first cluster view member is the coordinator
        // If we are the coordinator, only call ourself if 'excludeSelf' is false
        if (this.isCurrentNodeCoordinator()) {
            if (!excludeSelf) {
                // TODO: always do it this way?
                if (this.directlyInvokeLocal) {
                    new AsynchronousLocalInvocation(serviceName, methodName, args, types).invoke();
                }
                // else drop through
            } else {
                return;
            }
        }

        Address coord = this.groupView.coordinator;
        RequestOptions opt = new RequestOptions(ResponseMode.GET_ALL, this.getMethodCallTimeout(), false, new NoHandlerForRPCRspFilter());
        if (unordered) {
            opt.setFlags(Message.OOB);
        }
        try {
            this.dispatcher.callRemoteMethod(coord, m, opt);
        } catch (Exception e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Caught raw Throwable on remote invocation", e);
        }
    }

    // *************************
    // *************************
    // Group Membership listeners
    // *************************
    // *************************

    public boolean getAllowSynchronousMembershipNotifications() {
        return this.allowSyncListeners;
    }

    /**
     * Sets whether this partition will synchronously notify any HAPartition.HAMembershipListener of membership changes using
     * the calling thread from the underlying group communications layer (e.g. JGroups).
     *
     * @param allowSync <code>true</code> if registered listeners that don't implement
     *        <code>AsynchHAMembershipExtendedListener</code> or <code>AsynchHAMembershipListener</code> should be notified
     *        synchronously of membership changes; <code>false</code> if those listeners can be notified asynchronously. Default
     *        is <code>false</code>.
     */
    public void setAllowSynchronousMembershipNotifications(boolean allowSync) {
        this.allowSyncListeners = allowSync;
    }

    @Override
    public void registerGroupMembershipListener(GroupMembershipListener listener) {
        registerGroupMembershipListener(listener, false);
    }

    @Override
    public void unregisterGroupMembershipListener(GroupMembershipListener listener) {
        unregisterGroupMembershipListener(listener, false);
    }

    // *************************
    // *************************
    // State transfer management
    // *************************
    // *************************

    public long getStateTransferTimeout() {
        return this.state_transfer_timeout;
    }

    public void setStateTransferTimeout(long timeout) {
        this.state_transfer_timeout = timeout;
    }

    @Override
    public Future<SerializableStateTransferResult> getServiceState(String serviceName, ClassLoader classloader) {
        RunnableFuture<SerializableStateTransferResult> future = null;
        StateTransferTask<?, ?> task = stateTransferTasks.get(serviceName);
        if (task == null || (task.result != null && !task.result.stateReceived())) {
            SerializableStateTransferTask newTask = new SerializableStateTransferTask(serviceName, classloader);
            stateTransferTasks.put(serviceName, newTask);
            future = new FutureTask<SerializableStateTransferResult>(newTask);
        } else if (task instanceof SerializableStateTransferTask) {
            // Unlikely scenario
            log.warn("Received concurrent requests to get service state for " + serviceName);
            future = new FutureTask<SerializableStateTransferResult>((SerializableStateTransferTask) task);
        } else {
            throw new IllegalStateException("State transfer task for " + serviceName
                    + " that will return an input stream is already pending");
        }
        Executor e = getThreadPool();
        if (e == null) {
            e = Executors.newSingleThreadExecutor();
        }
        e.execute(future);
        return future;
    }

    @Override
    public Future<SerializableStateTransferResult> getServiceState(String serviceName) {
        return getServiceState(serviceName, null);
    }

    @Override
    public Future<StreamStateTransferResult> getServiceStateAsStream(String serviceName) {
        RunnableFuture<StreamStateTransferResult> future = null;
        StateTransferTask<?, ?> task = stateTransferTasks.get(serviceName);
        if (task == null || (task.result != null && !task.result.stateReceived())) {
            StreamStateTransferTask newTask = new StreamStateTransferTask(serviceName);
            stateTransferTasks.put(serviceName, newTask);
            future = new FutureTask<StreamStateTransferResult>(newTask);
        } else if (task instanceof StreamStateTransferTask) {
            // Unlikely scenario
            log.warn("Received concurrent requests to get service state for " + serviceName);
            future = new FutureTask<StreamStateTransferResult>((StreamStateTransferTask) task);
        } else {
            throw new IllegalStateException("State transfer task for " + serviceName
                    + " that will return an deserialized object is already pending");
        }
        Executor e = getThreadPool();
        if (e == null) {
            e = Executors.newSingleThreadExecutor();
        }
        e.execute(future);
        return future;
    }

    @Override
    public void registerStateTransferProvider(String serviceName, StateTransferProvider provider) {
        this.stateProviders.put(serviceName, provider);
    }

    @Override
    public void unregisterStateTransferProvider(String serviceName) {
        this.stateProviders.remove(serviceName);
    }

    // Public ------------------------------------------------------------------

    public String showHistory() {
        StringBuffer buff = new StringBuffer();
        Vector<String> data = new Vector<String>(this.history);
        for (java.util.Iterator<String> row = data.iterator(); row.hasNext();) {
            String info = row.next();
            buff.append(info).append("\n");
        }
        return buff.toString();
    }

    public String showHistoryAsXML() {
        StringBuffer buff = new StringBuffer();
        buff.append("<events>\n");
        Vector<String> data = new Vector<String>(this.history);
        for (java.util.Iterator<String> row = data.iterator(); row.hasNext();) {
            buff.append("   <event>\n      ");
            String info = row.next();
            buff.append(info);
            buff.append("\n   </event>\n");
        }
        buff.append("</events>\n");
        return buff.toString();
    }

    public Short getScopeId() {
        return scopeId;
    }

    public void setScopeId(Short scopeId) {
        this.scopeId = scopeId;
    }

    public int getMaxHistoryLength() {
        return maxHistoryLength;
    }

    public void setMaxHistoryLength(int maxHistoryLength) {
        this.maxHistoryLength = maxHistoryLength;
    }

    public Executor getThreadPool() {
        return this.threadPool;
    }

    public void setThreadPool(Executor threadPool) {
        this.threadPool = threadPool;
    }

    public String getJGroupsVersion() {
        return Version.description + "( " + Version.string_version + ")";
    }

    public ChannelFactory getChannelFactory() {
        return this.channelFactory;
    }

    public void setChannelFactory(ChannelFactory factory) {
        this.channelFactory = factory;
    }

    public String getChannelStackName() {
        return this.stackName;
    }

    public void setChannelStackName(String stackName) {
        this.stackName = stackName;
    }

    @Override
    public long getMethodCallTimeout() {
        return this.method_call_timeout;
    }

    public void setMethodCallTimeout(long timeout) {
        this.method_call_timeout = timeout;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
        this.groupName = channel.getClusterName();
    }

    // Lifecycle ----------------------------------------------------------------

    public void create() throws Exception {

        if (state == State.CREATED || state == State.STARTING || state == State.STARTED || state == State.STOPPING || state == State.STOPPED) {
            log.debug("Ignoring create call; current state is " + this.state);
            return;
        }

        createService();
        state = State.CREATED;
    }

    public void start() throws Exception {
        if (state == State.STARTING || state == State.STARTED || state == State.STOPPING) {
            log.debug("Ignoring start call; current state is " + this.state);
            return;
        }

        if (state != State.CREATED && state != State.STOPPED && state != State.FAILED) {
            log.debug("Start requested before create, calling create now");
            create();
        }

        state = State.STARTING;
        try {
            startService();
            state = State.STARTED;
        } catch (Throwable t) {
            state = State.FAILED;
            if (this.channel != null && this.channelSelfConnected) {
                this.log.debug("Caught exception after channel connected; closing channel -- " + t.getLocalizedMessage());
                this.channel.close();
                this.channel = null;
            }
            if (t instanceof Exception)
                throw (Exception) t;
            else if (t instanceof Error)
                throw (Error) t;
            else
                throw new RuntimeException(t);
        }
    }

    public void stop() {
        if (state != State.STARTED) {
            log.debug("Ignoring stop call; current state is " + this.state);
            return;
        }

        state = State.STOPPING;
        try {
            stopService();
            state = State.STOPPED;
        } catch (InterruptedException e) {
            state = State.FAILED;
            Thread.currentThread().interrupt();
            log.warn("Exception in stop ", e);
        } catch (Exception e) {
            state = State.FAILED;
            log.warn("Exception in stop ", e);
        } catch (Error e) {
            state = State.FAILED;
            throw e;
        }

    }

    public void destroy() {
        if (state == State.DESTROYED) {
            log.debug("Ignoring destroy call; current state is " + this.state);
            return;
        }

        if (state == State.STARTED) {
            log.debug("Destroy requested before stop, calling stop now");
            stop();
        }
        try {
            destroyService();
        } catch (Exception e) {
            log.error("Error destroying service", e);
        }
        state = State.DESTROYED;
    }

    public State getState() {
        return state;
    }

    // Protected --------------------------------------------------------------

    protected void createService() throws Exception {
        this.setupLoggers(this.getGroupName());

        // Create the asynchronous handler for view changes
        this.asynchHandler = new AsynchEventHandler(new ViewChangeEventProcessor(), "AsynchViewChangeHandler");
    }

    protected void startService() throws Exception {
        if (this.scopeId == null) {
            throw new IllegalStateException("Must set scopeId before calling start()");
        }

        this.stateIdPrefix = getClass().getName() + "." + this.scopeId + ".";

        if (this.channel == null || !this.channel.isOpen()) {
            this.log.debug("Creating Channel for partition " + this.getGroupName() + " using stack "
                    + this.getChannelStackName());

            this.channel = this.createChannel();
        }

        // Subscribe to events generated by the channel
        MembershipListener meml = new MembershipListenerImpl();
        MessageListener msgl = this.stateIdPrefix == null ? null : new MessageListenerImpl();
        this.dispatcher = new RpcHandler(this.scopeId.shortValue(), this.channel, msgl, meml, new RequestMarshallerImpl(),
                new ResponseMarshallerImpl());

        if (!this.channel.isConnected()) {
            this.channelSelfConnected = true;
            this.channel.connect(this.getGroupName());

            this.log.debug("Get current members");
            this.waitForView();
        } else {
            meml.viewAccepted(this.channel.getView());
            // Since we haven't triggered a flush, we need to manually open the gate to allow rpcs.
            this.flushBlockGate.open();
        }

        this.directlyInvokeLocal = this.channel.getDiscardOwnMessages();

        // get current JG group properties
        this.localJGAddress = this.channel.getAddress();
        this.me = this.nodeFactory.getClusterNode(localJGAddress);

        this.verifyNodeIsUnique();

        // Start the asynch listener handler thread
        this.asynchHandler.start();
    }

    protected void stopService() throws Exception {
        try {
            this.asynchHandler.stop();
        } catch (Exception e) {
            this.log.warn("Failed to stop asynchHandler", e);
        }

        // NR 200505 : [JBCLUSTER-38] replace channel.close() by a disconnect and
        // add the destroyPartition() step
        try {
            if (this.channelSelfConnected && this.channel != null && this.channel.isConnected()) {
                this.channelSelfConnected = false;
                this.channel.disconnect();
                this.channel.close();
            }
        } catch (Exception e) {
            this.log.error("channel disconnection failed", e);
        } finally {
            this.channel = null;
        }
    }

    protected void destroyService() {
        // no-op
    }

    protected Channel createChannel() {
        ChannelFactory factory = this.getChannelFactory();
        if (factory == null) {
            throw new IllegalStateException("HAPartitionConfig has no JChannelFactory");
        }
        String stack = this.getChannelStackName();
        if (stack == null) {
            throw new IllegalStateException("HAPartitionConfig has no multiplexer stack");
        }
        try {
            return factory.createChannel(this.getGroupName());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failure creating multiplexed Channel", e);
        }
    }

    protected Channel getChannel() {
        return this.channel;
    }

    protected void registerGroupMembershipListener(GroupMembershipListener listener, boolean sync) {
        if (sync && this.allowSyncListeners) {
            synchronized (this.syncMembershipListeners) {
                this.syncMembershipListeners.add(listener);
            }
        } else {
            synchronized (this.asyncMembershipListeners) {
                this.asyncMembershipListeners.add(listener);
            }
        }
    }

    protected void unregisterGroupMembershipListener(GroupMembershipListener listener, boolean sync) {
        if (sync && this.allowSyncListeners) {
            synchronized (this.syncMembershipListeners) {
                this.syncMembershipListeners.remove(listener);
            }
        } else {
            synchronized (this.asyncMembershipListeners) {
                this.asyncMembershipListeners.remove(listener);
            }
        }
    }

    protected void logHistory(String pattern, Object... args) {
        if (this.maxHistoryLength > 0) {
            try {
                List<Object> list = new ArrayList<Object>(args.length + 1);
                list.add(new Date());
                list.addAll(Arrays.asList(args));
                this.history.add(String.format("%c : " + pattern, list.toArray()));
                if (this.history.size() > this.maxHistoryLength) {
                    this.history.remove(0);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // Private -------------------------------------------------------

    /**
     * Creates an object from a byte buffer
     */
    Object objectFromByteBufferInternal(byte[] buffer, int offset, int length) throws Exception {
        if (buffer == null) return null;

        ByteArrayInputStream bais = new ByteArrayInputStream(buffer, offset, length);
        SwitchContext context = classLoaderSwitcher.getSwitchContext(this.getClass().getClassLoader());
        try {
            MarshalledValueInputStream mvis = new MarshalledValueInputStream(bais);
            return mvis.readObject();
        } finally {
            context.reset();
        }
    }

    /**
     * Serializes an object into a byte buffer. The object has to implement interface Serializable or Externalizable
     */
    byte[] objectToByteBufferInternal(Object obj) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MarshalledValueOutputStream mvos = new MarshalledValueOutputStream(baos);
        mvos.writeObject(obj);
        mvos.flush();
        return baos.toByteArray();
    }

    /**
     * Creates a response object from a byte buffer - optimized for response marshalling
     */
    Object objectFromByteBufferResponseInternal(byte[] buffer, int offset, int length) throws Exception {
        if (buffer == null) {
            return null;
        }

        if (buffer[offset] == NULL_VALUE) {
            return null;
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(buffer, offset, length);
        // read past the null/serializable byte
        bais.read();
        SwitchContext context = classLoaderSwitcher.getSwitchContext(this.getClass().getClassLoader());
        try {
            MarshalledValueInputStream mvis = new MarshalledValueInputStream(bais);
            return mvis.readObject();
        } finally {
            context.reset();
        }
    }

    /**
     * Serializes a response object into a byte buffer, optimized for response marshalling. The object has to implement
     * interface Serializable or Externalizable
     */
    byte[] objectToByteBufferResponseInternal(Object obj) throws Exception {
        if (obj == null) {
            return new byte[] { NULL_VALUE };
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // write a marker to stream to distinguish from null value stream
        baos.write(SERIALIZABLE_VALUE);
        MarshalledValueOutputStream mvos = new MarshalledValueOutputStream(baos);
        mvos.writeObject(obj);
        mvos.flush();
        return baos.toByteArray();
    }

    private void notifyChannelLock() {
        synchronized (this.channelLock) {
            this.channelLock.notifyAll();
        }
    }

    private <T> List<T> processResponseList(RspList<T> rspList, boolean trace) {
        List<T> result = new ArrayList<T>(rspList.size());
        if (rspList != null) {
            for (Rsp<T> response : rspList.values()) {
                // Only include received responses
                if (response.wasReceived()) {
                    result.add(response.getValue());
                } else if (trace) {
                    this.log.trace("Ignoring non-received response: " + response);
                }
            }

        }
        return result;
    }

    GroupView processViewChange(View newView) throws Exception {
        GroupView oldMembers = this.groupView;
        GroupView newGroupView = new GroupView(newView, oldMembers, this.nodeFactory);

        this.logHistory("New view: %s with viewId: %s (old view: %s)", newGroupView.allMembers, newGroupView.viewId, newGroupView.allMembers);

        this.groupView = newGroupView;

        if (oldMembers.viewId == -1) {
            // Initial viewAccepted
            this.log.debugf("ViewAccepted: initial members set for partition %s: %s (%s)", this.getGroupName(), newGroupView.viewId, this.groupView);

            this.log.infof("Number of cluster members: %s", newGroupView.allMembers.size());
            for (ClusterNode node : newGroupView.allMembers) {
                this.log.debug(node);
            }

            // Wake up the deployer thread blocking in waitForView
            this.notifyChannelLock();
        } else {
            int difference = newGroupView.allMembers.size() - oldMembers.allMembers.size();

            boolean merge = newView instanceof MergeView;
            if (this.isCurrentNodeCoordinator()) {
                this.clusterLifeCycleLog.infof("New cluster view for partition %s (id: %s, delta: %s, merge: %s) : %s", this.getGroupName(), newGroupView.viewId, difference, merge, newGroupView.allMembers);
            } else {
                this.log.infof("New cluster view for partition %s: %s (%s delta: %s, merge: %s)", this.getGroupName(), newGroupView.viewId, this.groupView, difference, merge);
            }

            this.log.debugf("dead members: %s", newGroupView.deadMembers);
            this.log.debugf("membership changed from %s to %s", oldMembers.allMembers.size(), newGroupView.allMembers.size());
            // Put the view change to the asynch queue
            this.asynchHandler.queueEvent(newGroupView);

            // Broadcast the new view to the synchronous view change listeners
            if (this.allowSyncListeners) {
                this.notifyListeners(this.syncMembershipListeners, newGroupView.viewId, newGroupView.allMembers, newGroupView.deadMembers, newGroupView.newMembers, newGroupView.originatingGroups);
            }
        }

        return newGroupView;
    }

    private void waitForView() throws Exception {
        boolean intr = false;
        try {
            synchronized (this.channelLock) {
                if (this.getCurrentViewId() == -1) {
                    try {
                        this.channelLock.wait(this.getMethodCallTimeout());
                    } catch (InterruptedException iex) {
                        intr = true;
                    }

                    if (this.groupView == null) {
                        throw new IllegalStateException("No view received from Channel");
                    }
                }
            }
        } finally {
            if (intr)
                Thread.currentThread().interrupt();
        }
    }

    private void setupLoggers(String partitionName) {
        if (partitionName == null) {
            this.log = Logger.getLogger(getClass().getName());
            this.clusterLifeCycleLog = Logger.getLogger(getClass().getName() + ".lifecycle");
        } else {
            this.log = Logger.getLogger(getClass().getName() + "." + partitionName);
            this.clusterLifeCycleLog = Logger.getLogger(getClass().getName() + ".lifecycle." + partitionName);
        }
    }

    private void verifyNodeIsUnique() throws IllegalStateException {
        ClusterNodeImpl matched = null;
        for (ClusterNode member : this.getClusterNodes()) {
            if (member.equals(this.me)) {
                if (matched == null) {
                    // We of course are in the view, so we expect one match
                    // Just track that we've had one
                    matched = (ClusterNodeImpl) member;
                } else {
                    // Two nodes in view match us; try to figure out which one isn't us
                    ClusterNodeImpl other = matched;
                    if (other.getOriginalJGAddress().equals(((ClusterNodeImpl) this.me).getOriginalJGAddress())) {
                        other = (ClusterNodeImpl) member;
                    }
                    throw new IllegalStateException(String.format("Found member %s in current view that duplicates us (%s). This node cannot join partition until duplicate member has been removed", other, this.me));
                }
            }
        }
    }

    static List<ClusterNode> translateAddresses(List<Address> addresses, ClusterNodeFactory factory) {
        if (addresses == null) {
            return null;
        }

        List<ClusterNode> result = new ArrayList<ClusterNode>(addresses.size());
        for (Address address : addresses) {
            result.add(factory.getClusterNode(address));
        }

        return result;
    }

    /**
     * Helper method that returns a vector of dead members from two input vectors: new and old vectors of two views. Dead
     * members are old - new members.
     *
     * @param oldMembers Vector of old members
     * @param newMembers Vector of new members
     * @return Vector of members that have died between the two views, can be empty.
     */
    static List<ClusterNode> getDeadMembers(List<ClusterNode> oldMembers, List<ClusterNode> newMembers) {
        if (oldMembers == null) {
            oldMembers = new ArrayList<ClusterNode>();
        }
        if (newMembers == null) {
            newMembers = new ArrayList<ClusterNode>();
        }
        List<ClusterNode> dead = new ArrayList<ClusterNode>(oldMembers);
        dead.removeAll(newMembers);
        return dead;
    }

    /**
     * Helper method that returns a vector of new members from two input vectors: new and old vectors of two views.
     *
     * @param oldMembers Vector of old members
     * @param allMembers Vector of new members
     * @return Vector of members that have joined the partition between the two views
     */
    static List<ClusterNode> getNewMembers(List<ClusterNode> oldMembers, List<ClusterNode> allMembers) {
        if (oldMembers == null) {
            oldMembers = new ArrayList<ClusterNode>();
        }
        if (allMembers == null) {
            allMembers = new ArrayList<ClusterNode>();
        }
        List<ClusterNode> newMembers = new ArrayList<ClusterNode>(allMembers);
        newMembers.removeAll(oldMembers);
        return newMembers;
    }

    void notifyListeners(List<GroupMembershipListener> listeners, long viewID, List<ClusterNode> allMembers, List<ClusterNode> deadMembers, List<ClusterNode> newMembers, List<List<ClusterNode>> originatingGroups) {
        this.log.debugf("Begin notifyListeners, viewID: %s", viewID);
        for (GroupMembershipListener listener : listeners) {
            try {
                if (originatingGroups != null) {
                    listener.membershipChangedDuringMerge(deadMembers, newMembers, allMembers, originatingGroups);
                } else {
                    listener.membershipChanged(deadMembers, newMembers, allMembers);
                }
            } catch (Throwable e) {
                // a problem in a listener should not prevent other members to receive the new view
                this.log.warnf(e, "Membership listener callback failure: %s", listener);
            }
        }

        this.log.debugf("End notifyListeners, viewID: %s", viewID);
    }

    // Inner classes -------------------------------------------------

    /**
     * A simple data class containing the current view information as well as change information needed to notify the
     * GroupMembershipListeners about the event that led to this view.
     */
    protected static class GroupView {
        protected final long viewId;
        protected final List<ClusterNode> deadMembers;
        protected final List<ClusterNode> newMembers;
        protected final List<ClusterNode> allMembers;
        protected final List<List<ClusterNode>> originatingGroups;
        protected final List<Address> jgmembers;
        protected final Address coordinator;

        GroupView() {
            this.viewId = -1;
            this.deadMembers = new ArrayList<ClusterNode>();
            this.newMembers = this.allMembers = new ArrayList<ClusterNode>();
            this.jgmembers = new ArrayList<Address>();
            this.coordinator = null;
            this.originatingGroups = null;
        }

        GroupView(View newView, GroupView previousView, ClusterNodeFactory factory) {
            this.viewId = newView.getVid().getId();
            this.jgmembers = new ArrayList<Address>(newView.getMembers());
            this.coordinator = this.jgmembers.size() == 0 ? null : this.jgmembers.get(0);
            this.allMembers = translateAddresses(newView.getMembers(), factory);
            this.deadMembers = getDeadMembers(previousView.allMembers, allMembers);
            this.newMembers = getNewMembers(previousView.allMembers, allMembers);
            if (newView instanceof MergeView) {
                MergeView mergeView = (MergeView) newView;
                List<View> subgroups = mergeView.getSubgroups();
                this.originatingGroups = new ArrayList<List<ClusterNode>>(subgroups.size());
                for (View view : subgroups) {
                    this.originatingGroups.add(translateAddresses(view.getMembers(), factory));
                }
            } else {
                this.originatingGroups = null;
            }
        }
    }

    /**
     * Marshalls request payloads for transmission across the cluster.
     */
    class RequestMarshallerImpl implements org.jgroups.blocks.RpcDispatcher.Marshaller {

        @Override
        public Buffer objectToBuffer(Object obj) throws Exception {
            // wrap MethodCall in Object[service_name, byte[]] so that service name is available during demarshalling
            if (obj instanceof MethodCall) {
                String name = ((MethodCall) obj).getName();
                int idx = name.lastIndexOf('.');
                String serviceName = name.substring(0, idx);
                return new Buffer(CoreGroupCommunicationService.this.objectToByteBufferInternal(new Object[] { serviceName, CoreGroupCommunicationService.this.objectToByteBufferInternal(obj) }));
            }

            return new Buffer(CoreGroupCommunicationService.this.objectToByteBufferInternal(obj));
        }

        @Override
        public Object objectFromBuffer(byte[] buf, int offset, int length) throws Exception {
            return CoreGroupCommunicationService.this.objectFromByteBufferInternal(buf, offset, length);
        }
    }

    /**
     * Marshalls responses for transmission across the cluster.
     */
    class ResponseMarshallerImpl implements org.jgroups.blocks.RpcDispatcher.Marshaller {

        @Override
        public Buffer objectToBuffer(Object obj) throws Exception {
            return new Buffer(CoreGroupCommunicationService.this.objectToByteBufferResponseInternal(obj));
        }

        @Override
        public Object objectFromBuffer(byte[] buf, int offset, int length) throws Exception {
            Object retval = CoreGroupCommunicationService.this.objectFromByteBufferResponseInternal(buf, offset, length);
            // HAServiceResponse is only received when a scoped classloader is required for unmarshalling
            if (!(retval instanceof HAServiceResponse)) {
                return retval;
            }

            String serviceName = ((HAServiceResponse) retval).getServiceName();
            byte[] payload = ((HAServiceResponse) retval).getPayload();

            WeakReference<ClassLoader> weak = CoreGroupCommunicationService.this.clmap.get(serviceName);
            SwitchContext context = CoreGroupCommunicationService.this.classLoaderSwitcher.getSwitchContext((weak != null) ? weak.get() : CoreGroupCommunicationService.class.getClassLoader());
            try {
                return CoreGroupCommunicationService.this.objectFromByteBufferResponseInternal(payload, offset, length);
            } finally {
                context.reset();
            }
        }
    }

    /**
     * Overrides RpcDispatcher.Handle so that we can dispatch to many different objects.
     */
    private class RpcHandler extends MuxRpcDispatcher {
        RpcHandler(short scopeId, Channel channel, MessageListener messageListener, MembershipListener membershipListener, Marshaller reqMarshaller, Marshaller rspMarshaller) {
            super(scopeId);

            setMessageListener(messageListener);
            setMembershipListener(membershipListener);
            setRequestMarshaller(reqMarshaller);
            setResponseMarshaller(rspMarshaller);
            setChannel(channel);
            channel.addChannelListener(this);
            start();
        }

        /**
         * Analyze the MethodCall contained in <code>req</code> to find the registered service object to invoke against, and
         * then execute it against *that* object and return result.
         *
         * This overrides RpcDispatcher.Handle so that we can dispatch to many different objects.
         *
         * @param req The org.jgroups. representation of the method invocation
         * @return The serializable return value from the invocation
         */
        @Override
        public Object handle(Message req) {
            Object body = null;
            Object retval = null;
            Object handler = null;
            boolean trace = this.log.isTraceEnabled();
            String service = null;
            byte[] request_bytes = null;

            if (trace) {
                this.log.trace("Partition " + CoreGroupCommunicationService.this.getGroupName() + " received msg");
            }
            if (req == null || req.getRawBuffer() == null) {
                this.log.warn("Partition " + CoreGroupCommunicationService.this.getGroupName()
                        + " message or message buffer is null!");
                return null;
            }

            try {
                Object wrapper = CoreGroupCommunicationService.this.objectFromByteBufferInternal(req.getRawBuffer(), req.getOffset(), req.getLength());
                if (wrapper == null || !(wrapper instanceof Object[])) {
                    this.log.warn("Partition " + CoreGroupCommunicationService.this.getGroupName()
                            + " message wrapper does not contain Object[] object!");
                    return null;
                }

                // wrapper should be Object[]{service_name, byte[]}
                Object[] temp = (Object[]) wrapper;
                service = (String) temp[0];
                request_bytes = (byte[]) temp[1];

                // see if this node has registered to handle this service
                handler = CoreGroupCommunicationService.this.rpcHandlers.get(service);
                if (handler == null) {
                    if (trace) {
                        this.log.trace("Partition " + CoreGroupCommunicationService.this.getGroupName()
                                + " no rpc handler registered under service " + service);
                    }
                    return new NoHandlerForRPC();
                }
            } catch (Exception e) {
                this.log.warn("Partition " + CoreGroupCommunicationService.this.getGroupName()
                        + " failed unserializing message buffer (msg=" + req + ")", e);
                return null;
            }

            // If client registered the service with a classloader, override the thread classloader here
            WeakReference<ClassLoader> weak = CoreGroupCommunicationService.this.clmap.get(service);
            SwitchContext context = CoreGroupCommunicationService.this.classLoaderSwitcher.getSwitchContext((weak != null) ? weak.get() : CoreGroupCommunicationService.class.getClassLoader());
            try {
                body = CoreGroupCommunicationService.this.objectFromByteBufferInternal(request_bytes, 0, request_bytes.length);
            } catch (Exception e) {
                this.log.warn("Partition " + CoreGroupCommunicationService.this.getGroupName()
                        + " failed extracting message body from request bytes", e);
                return null;
            } finally {
                context.reset();
            }

            if (body == null || !(body instanceof MethodCall)) {
                this.log.warn("Partition " + CoreGroupCommunicationService.this.getGroupName()
                        + " message does not contain a MethodCall object!");
                return null;
            }

            // get method call information
            MethodCall method_call = (MethodCall) body;
            String methodName = method_call.getName();

            if (trace) {
                this.log.trace("full methodName: " + methodName);
            }

            int idx = methodName.lastIndexOf('.');
            String handlerName = methodName.substring(0, idx);
            String newMethodName = methodName.substring(idx + 1);
            if (trace) {
                this.log.trace("handlerName: " + handlerName + " methodName: " + newMethodName);
                this.log.trace("Handle: " + methodName);
            }

            // prepare method call
            method_call.setName(newMethodName);

            /*
             * Invoke it and just return any exception with trace level logging of the exception. The exception semantics of a
             * group rpc call are weak as the return value may be a normal return value or the exception thrown.
             */
            try {
                retval = method_call.invoke(handler);
                if (weak != null) {
                    // wrap the response so that the service name can be accessed during unmarshalling of the response
                    byte[] retbytes = CoreGroupCommunicationService.this.objectToByteBufferResponseInternal(retval);
                    retval = new HAServiceResponse(handlerName, retbytes);
                }
                if (trace) {
                    this.log.trace("rpc call return value: " + retval);
                }
            } catch (Throwable t) {
                if (trace) {
                    this.log.trace("Partition " + CoreGroupCommunicationService.this.getGroupName()
                            + " rpc call threw exception", t);
                }
                retval = t;
            }

            return retval;
        }
    }

    /**
     * Handles callbacks from the thread that asynchronously deals with view change events.
     */
    class ViewChangeEventProcessor implements AsynchEventHandler.AsynchEventProcessor {
        @Override
        public void processEvent(Object event) {
            GroupView vce = (GroupView) event;
            CoreGroupCommunicationService.this.notifyListeners(CoreGroupCommunicationService.this.asyncMembershipListeners,
                    vce.viewId, vce.allMembers, vce.deadMembers, vce.newMembers, vce.originatingGroups);

        }
    }

    /**
     * Copyright (c) 2005 Brian Goetz and Tim Peierls Released under the Creative Commons Attribution License
     * (http://creativecommons.org/licenses/by/2.5) Official home: http://www.jcip.net
     *
     * ThreadGate
     * <p/>
     * Recloseable gate using wait and notifyAll
     *
     * @author Brian Goetz and Tim Peierls
     */

    static class ThreadGate {
        private static final int OPEN = 1;
        private static final int CLOSED = -1;

        private static class Sync extends AbstractQueuedSynchronizer {
            /** The serialVersionUID */
            private static final long serialVersionUID = 1L;

            Sync(int state) {
                this.setState(state);
            }

            @Override
            protected int tryAcquireShared(int ingored) {
                return this.getState();
            }

            @Override
            protected boolean tryReleaseShared(int state) {
                this.setState(state);
                return true;
            }
        }

        private final Sync sync = new Sync(CLOSED);

        public void open() {
            this.sync.releaseShared(OPEN);
        }

        public void close() {
            this.sync.releaseShared(CLOSED);
        }

        public boolean await(long timeout) throws InterruptedException {
            return this.sync.tryAcquireSharedNanos(0, TimeUnit.MILLISECONDS.toNanos(timeout));
        }
    }

    /**
     * Converts JGroups address objects into ClusterNode
     */
    class ClusterNodeFactoryImpl implements ClusterNodeFactory {
        private final ConcurrentMap<Address, IpAddress> addressMap = new ConcurrentHashMap<Address, IpAddress>();

        @Override
        public ClusterNode getClusterNode(Address a) {
            IpAddress result = addressMap.get(a);
            if (result == null) {
                result = (IpAddress) channel.down(new Event(Event.GET_PHYSICAL_ADDRESS, a));
                if (result == null) {
                    throw new IllegalStateException(String.format("Address %s not registered in transport layer", a));
                }
                addressMap.put(a, result);
            }
            InetSocketAddress socketAddress = new InetSocketAddress(result.getIpAddress(), result.getPort());
            String id = channel.getName(a);
            if (id == null) {
                id = socketAddress.getAddress().getHostAddress() + ":" + socketAddress.getPort();
            }
            return new ClusterNodeImpl(id, a, socketAddress);
        }
    }

    /**
     * Returned when an RPC call arrives for a service that isn't registered.
     */
    public static class NoHandlerForRPC implements Serializable {
        static final long serialVersionUID = -1263095408483622838L;
    }

    /**
     * Used internally when an RPC call requires a custom classloader for unmarshalling
     */
    private static class HAServiceResponse implements Serializable {
        private static final long serialVersionUID = -6485594652749906437L;
        private final String serviceName;
        private final byte[] payload;

        public HAServiceResponse(String serviceName, byte[] payload) {
            this.serviceName = serviceName;
            this.payload = payload;
        }

        public String getServiceName() {
            return this.serviceName;
        }

        public byte[] getPayload() {
            return this.payload;
        }
    }

    /**
     * Handles MembershipListener callbacks from JGroups Channel
     */
    class MembershipListenerImpl implements MembershipListener {
        @Override
        public void suspect(org.jgroups.Address suspected_mbr) {
            CoreGroupCommunicationService.this.logHistory("Node suspected: %s", (suspected_mbr == null ? "null" : suspected_mbr.toString()));
            if (CoreGroupCommunicationService.this.isCurrentNodeCoordinator()) {
                CoreGroupCommunicationService.this.clusterLifeCycleLog.infof("Suspected member: %s", suspected_mbr);
            } else {
                CoreGroupCommunicationService.this.log.infof("Suspected member: %s", suspected_mbr);
            }
        }

        @Override
        public void block() {
            CoreGroupCommunicationService.this.flushBlockGate.close();
            CoreGroupCommunicationService.this.log.debugf("Block processed at %s", CoreGroupCommunicationService.this.me);
        }

        @Override
        public void unblock() {
            CoreGroupCommunicationService.this.flushBlockGate.open();
            CoreGroupCommunicationService.this.log.debugf("Unblock processed at %s", CoreGroupCommunicationService.this.me);
        }

        /**
         * Notification of a cluster view change. This is done from the JG protocol handler thread and we must be careful to not
         * unduly block this thread. Because of this there are two types of listeners, synchronous and asynchronous. The
         * synchronous listeners are messaged with the view change event using the calling thread while the asynchronous
         * listeners are messaged using a separate thread.
         *
         * @param newView
         */
        @Override
        public void viewAccepted(View newView) {
            try {
                processViewChange(newView);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                CoreGroupCommunicationService.this.log.error("ViewAccepted failed", ex);
            } catch (Exception ex) {
                CoreGroupCommunicationService.this.log.error("ViewAccepted failed", ex);
            }
        }
    }

    /**
     * Handles MessageListener callbacks from the JGroups layer.
     */
    class MessageListenerImpl implements MessageListener {
        @Override
        public void receive(org.jgroups.Message msg) {
            // no-op
        }

        @Override
        public void getState(OutputStream stream) {
            System.err.println("MessageListenerImpl.getState(...)");
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream));
            try {
                for (Map.Entry<String, StateTransferProvider> entry: stateProviders.entrySet()) {
                    String serviceName = entry.getKey();
                    out.writeUTF(serviceName);
                    StateTransferProvider provider = entry.getValue();
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    new MarshalledValueOutputStream(output).writeObject(provider.getCurrentState());
                    byte[] bytes = output.toByteArray();
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            } catch (IOException e) {
                CoreGroupCommunicationService.this.log.error("getState failed", e);
            }
        }

        @Override
        public void setState(InputStream stream) {
            System.err.println("MessageListenerImpl.setState(...)");
            DataInputStream input = new DataInputStream(stream);
            try {
                while (input.available() > 0) {
                    String serviceName = input.readUTF();
                    StateTransferTask<?, ?> task = CoreGroupCommunicationService.this.stateTransferTasks.remove(serviceName);
                    int length = input.readInt();
                    if (task != null) {
                        System.err.println(String.format("Handing off state transfer of %s to async task.", serviceName));
                        byte[] bytes = new byte[length];
                        input.read(bytes);
                        task.setState(bytes);
                    } else {
                        System.err.println(String.format("State retrieved for %s service but no transfer task exists.", serviceName));
                        input.skipBytes(length);
                    }
                }
            } catch (IOException e) {
                CoreGroupCommunicationService.this.log.error("setState failed", e);
            }
        }
    }

    /**
     * Allows a state transfer request to be executed asynchronously.
     */
    private abstract class StateTransferTask<T extends StateTransferResult, V> implements Callable<T> {
        private final String serviceName;
        V state;
        private boolean isStateSet;
        private Exception setStateException;
        T result;
        private final Object callMutex = new Object();

        StateTransferTask(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public T call() throws Exception {
            synchronized (callMutex) {
                if (result != null)  return result;

                try {
                    long start, stop;
                    this.isStateSet = false;
                    start = System.currentTimeMillis();
                    try {
                        System.err.println("StateTransferTask.call(...) invoking channel.getState(...)");
                        CoreGroupCommunicationService.this.getChannel().getState(null, CoreGroupCommunicationService.this.getStateTransferTimeout());
                        System.err.println("Waiting to be notified.");
                        synchronized (this) {
                            while (!this.isStateSet) {
                                if (this.setStateException != null) {
                                    throw this.setStateException;
                                }

                                try {
                                    wait();
                                } catch (InterruptedException iex) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                        stop = System.currentTimeMillis();
                        CoreGroupCommunicationService.this.log.debugf("serviceState was retrieved successfully (in %d milliseconds)", stop - start);
                        return createStateTransferResult(true, state, null);
                    } catch (StateTransferException e) {
                        e.printStackTrace(System.err);
                        System.err.println("StateTransferException -- waiting for channel lock notify");
                        // No one provided us with serviceState.
                        // We need to find out if we are the coordinator, so we must
                        // block until viewAccepted() is called at least once
                        synchronized (CoreGroupCommunicationService.this.channelLock) {
                            while (CoreGroupCommunicationService.this.getCurrentView().size() == 0) {
                                CoreGroupCommunicationService.this.log.debug("waiting on viewAccepted()");
                                try {
                                    CoreGroupCommunicationService.this.channelLock.wait();
                                } catch (InterruptedException iex) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }

                        if (CoreGroupCommunicationService.this.isCurrentNodeCoordinator()) {
                            CoreGroupCommunicationService.this.log.debugf("State could not be retrieved for service %s (we are the first member in group)", serviceName);
                        } else {
                            throw new IllegalStateException("Initial serviceState transfer failed: Channel.getState() returned false");
                        }
                    }

                    return createStateTransferResult(false, state, null);
                } catch (Exception e) {
                    return createStateTransferResult(false, null, e);
                }
            }
        }

        protected abstract T createStateTransferResult(boolean gotState, V state, Exception exception);

        void setState(byte[] state) {
            try {
                if (state == null) {
                    CoreGroupCommunicationService.this.log.debugf("transferred state for service %s is null (may be first member in cluster)", serviceName);
                } else {
                    ByteArrayInputStream bais = new ByteArrayInputStream(state);
                    setState(bais);
                    bais.close();
                }

                this.isStateSet = true;
            } catch (Throwable t) {
                recordSetStateFailure(t);
            } finally {
                // Notify waiting thread that serviceState has been set.
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        protected abstract void setState(InputStream is) throws IOException, ClassNotFoundException;

        private void recordSetStateFailure(Throwable t) {
            CoreGroupCommunicationService.this.log.errorf(t, "failed setting serviceState for service %s", serviceName);
            if (t instanceof Exception) {
                this.setStateException = (Exception) t;
            } else {
                this.setStateException = new Exception(t);
            }
        }
    }

    private class SerializableStateTransferTask extends StateTransferTask<SerializableStateTransferResult, Serializable> {
        private final WeakReference<ClassLoader> classloader;

        SerializableStateTransferTask(String serviceName, ClassLoader cl) {
            super(serviceName);
            if (cl != null) {
                classloader = null;
            } else {
                classloader = new WeakReference<ClassLoader>(cl);
            }
        }

        @Override
        protected SerializableStateTransferResult createStateTransferResult(final boolean gotState, final Serializable state,
                final Exception exception) {
            return new SerializableStateTransferResult() {
                @Override
                public Serializable getState() {
                    return state;
                }

                @Override
                public Exception getStateTransferException() {
                    return exception;
                }

                @Override
                public boolean stateReceived() {
                    return gotState;
                }
            };
        }

        @Override
        protected void setState(InputStream is) throws IOException, ClassNotFoundException {
            ClassLoader cl = getStateTransferClassLoader();
            SwitchContext switchContext = CoreGroupCommunicationService.this.classLoaderSwitcher.getSwitchContext(cl);
            try {
                MarshalledValueInputStream mvis = new MarshalledValueInputStream(is);
                this.state = (Serializable) mvis.readObject();
            } finally {
                switchContext.reset();
            }
        }

        private ClassLoader getStateTransferClassLoader() {
            ClassLoader cl = classloader == null ? null : classloader.get();
            if (cl == null) {
                cl = this.getClass().getClassLoader();
            }
            return cl;
        }
    }

    private class StreamStateTransferTask extends StateTransferTask<StreamStateTransferResult, InputStream> {
        StreamStateTransferTask(String serviceName) {
            super(serviceName);
        }

        @Override
        protected StreamStateTransferResult createStateTransferResult(final boolean gotState, final InputStream state, final Exception exception) {
            return new StreamStateTransferResult() {
                @Override
                public InputStream getState() {
                    return state;
                }

                @Override
                public Exception getStateTransferException() {
                    return exception;
                }

                @Override
                public boolean stateReceived() {
                    return gotState;
                }
            };
        }

        @Override
        protected void setState(InputStream is) throws IOException, ClassNotFoundException {
            this.state = is;
        }
    }

    /**
     * Uses the service's thread pool to asynchronously invoke on the local object.
     */
    private class AsynchronousLocalInvocation implements Runnable {
        private final String serviceName;
        private final String methodName;
        private final Object[] args;
        private final Class<?>[] types;

        AsynchronousLocalInvocation(String serviceName, String methodName, Object[] args, Class<?>[] types) {
            this.serviceName = serviceName;
            this.methodName = methodName;
            this.args = args;
            this.types = types;
        }

        @Override
        public void run() {
            try {
                CoreGroupCommunicationService.this.invokeDirectly(serviceName, methodName, args, types, null, null);
            } catch (Exception e) {
                log.warnf(e, "Caught exception asynchronously invoking method %s on service %s", methodName, serviceName);
            }
        }

        public void invoke() {
            if (CoreGroupCommunicationService.this.threadPool != null) {
                CoreGroupCommunicationService.this.threadPool.execute(this);
            } else {
                // Just do it synchronously
                run();
            }
        }
    }

    private class NoHandlerForRPCRspFilter implements RspFilter {
        private final RspFilter filter;

        NoHandlerForRPCRspFilter() {
            this.filter = null;
        }

        NoHandlerForRPCRspFilter(ResponseFilter filter) {
            this.filter = (filter != null) ? new RspFilterAdapter(filter, CoreGroupCommunicationService.this.nodeFactory) : null;
        }

        @Override
        public boolean isAcceptable(Object response, Address sender) {
            return !(response instanceof NoHandlerForRPC) && ((filter == null) || filter.isAcceptable(response, sender));
        }

        @Override
        public boolean needMoreResponses() {
            return (filter == null) || filter.needMoreResponses();
        }
    }
}
