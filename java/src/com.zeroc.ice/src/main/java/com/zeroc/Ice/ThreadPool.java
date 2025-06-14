// Copyright (c) ZeroC, Inc.

package com.zeroc.Ice;

import com.zeroc.Ice.Instrumentation.CommunicatorObserver;
import com.zeroc.Ice.Instrumentation.ThreadObserver;
import com.zeroc.Ice.Instrumentation.ThreadState;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class ThreadPool implements Executor {
    final class ShutdownWorkItem implements ThreadPoolWorkItem {
        @Override
        public void execute(ThreadPoolCurrent current) {
            current.ioCompleted();
            try {
                _instance.objectAdapterFactory().shutdown();
            } catch (CommunicatorDestroyedException ex) {}
        }
    }

    static final class FinishedWorkItem implements ThreadPoolWorkItem {
        public FinishedWorkItem(EventHandler handler, boolean close) {
            _handler = handler;
            _close = close;
        }

        @Override
        public void execute(ThreadPoolCurrent current) {
            _handler.finished(current, _close);
        }

        private final EventHandler _handler;
        private final boolean _close;
    }

    static final class JoinThreadWorkItem implements ThreadPoolWorkItem {
        public JoinThreadWorkItem(EventHandlerThread thread) {
            _thread = thread;
        }

        @Override
        public void execute(ThreadPoolCurrent current) {
            // No call to ioCompleted, this shouldn't block (and we don't want to cause a new thread
            // to be started).
            try {
                _thread.join();
            } catch (InterruptedException e) {
                // Ignore.
            }
        }

        private final EventHandlerThread _thread;
    }

    static final class InterruptWorkItem implements ThreadPoolWorkItem {
        @Override
        public void execute(ThreadPoolCurrent current) {
            // Nothing to do, this is just used to interrupt the thread pool selector.
        }
    }

    // Handler work item for queuing ready handlers
    static final class HandlerWorkItem implements ThreadPoolWorkItem {
        public HandlerWorkItem(EventHandler handler, int operation) {
            _handler = handler;
            _operation = operation;
        }

        @Override
        public void execute(ThreadPoolCurrent current) {
            current.operation = _operation;
            current._handler = _handler;
	    current._ioCompleted = false;

            current._thread.setState(ThreadState.ThreadStateInUseForIO);
 
            // Check if handler is still valid and interested in this operation
            if ((_handler._registered & _operation) == 0 || (_handler._disabled & _operation) != 0) {
                current.ioCompleted();
                return;
            }
            
            try {
                _handler.message(current);
            } catch (Exception ex) {
                String s = "exception in `" + current._threadPool._prefix + "':\n" + Ex.toString(ex);
                s += "\nevent handler: " + _handler.toString();
                current._threadPool._instance.initializationData().logger.error(s);
            }
            
            if (!current._ioCompleted) {
                current.ioCompleted();
            }
        }

        private final EventHandler _handler;
        private final int _operation;
    }

    //
    // Exception raised by the thread pool work queue when the thread pool is destroyed.
    //
    static final class DestroyedException extends RuntimeException {
        private static final long serialVersionUID = -6665535975321237670L;
    }

    public ThreadPool(Instance instance, String prefix, int timeout) {
        Properties properties = instance.initializationData().properties;

        _instance = instance;
        _executor = instance.initializationData().executor;
        _destroyed = false;
        _prefix = prefix;
        _selector = new Selector(instance);
        _threadIndex = 0;
        _inUse = 0;
        _serialize = properties.getPropertyAsInt(_prefix + ".Serialize") > 0;
        _serverIdleTime = timeout;
        _threadPrefix = Util.createThreadName(properties, _prefix);

        // Initialize selector thread control
        _selectorRunning = new AtomicBoolean(false);
        _workQueue = new LinkedBlockingQueue<>();

        int nProcessors = Runtime.getRuntime().availableProcessors();

        //
        // We use just one thread as the default. This is the fastest possible setting, still allows
        // one level of nesting, and doesn't require to make the servants thread safe.
        //
        int size = properties.getPropertyAsIntWithDefault(_prefix + ".Size", 1);
        if (size < 1) {
            String s = _prefix + ".Size < 1; Size adjusted to 1";
            _instance.initializationData().logger.warning(s);
            size = 1;
        }

        int sizeMax = properties.getPropertyAsIntWithDefault(_prefix + ".SizeMax", size);
        if (sizeMax == -1) {
            sizeMax = nProcessors;
        }
        if (sizeMax < size) {
            String s =
                _prefix
                    + ".SizeMax < "
                    + _prefix
                    + ".Size; SizeMax adjusted to Size ("
                    + size
                    + ")";
            _instance.initializationData().logger.warning(s);
            sizeMax = size;
        }

        int sizeWarn = properties.getPropertyAsInt(_prefix + ".SizeWarn");
        if (sizeWarn != 0 && sizeWarn < size) {
            String s =
                _prefix
                    + ".SizeWarn < "
                    + _prefix
                    + ".Size; adjusted SizeWarn to Size ("
                    + size
                    + ")";
            _instance.initializationData().logger.warning(s);
            sizeWarn = size;
        } else if (sizeWarn > sizeMax) {
            String s =
                _prefix
                    + ".SizeWarn > "
                    + _prefix
                    + ".SizeMax; adjusted SizeWarn to SizeMax ("
                    + sizeMax
                    + ")";
            _instance.initializationData().logger.warning(s);
            sizeWarn = sizeMax;
        }

        int threadIdleTime =
            properties.getPropertyAsIntWithDefault(_prefix + ".ThreadIdleTime", 60);
        if (threadIdleTime < 0) {
            String s = _prefix + ".ThreadIdleTime < 0; ThreadIdleTime adjusted to 0";
            _instance.initializationData().logger.warning(s);
            threadIdleTime = 0;
        }

        _size = size;
        _sizeMax = sizeMax;
        _sizeWarn = sizeWarn;
        _sizeIO = Math.min(sizeMax, nProcessors);
        _threadIdleTime = threadIdleTime;

        int stackSize = properties.getPropertyAsInt(_prefix + ".StackSize");
        if (stackSize < 0) {
            String s = _prefix + ".StackSize < 0; Size adjusted to JRE default";
            _instance.initializationData().logger.warning(s);
            stackSize = 0;
        }
        _stackSize = stackSize;

        boolean hasPriority = properties.getProperty(_prefix + ".ThreadPriority").length() > 0;
        int priority = properties.getPropertyAsInt(_prefix + ".ThreadPriority");
        if (!hasPriority) {
            hasPriority = properties.getIceProperty("Ice.ThreadPriority").length() > 0;
            priority = properties.getIcePropertyAsInt("Ice.ThreadPriority");
        }
        _hasPriority = hasPriority;
        _priority = priority;

        // Start selector thread for work queue pattern
        _selectorRunning.set(true);
        _selectorThread = new SelectorThread();
        _selectorThread.start();

        if (_instance.traceLevels().threadPool >= 1) {
            String s =
                "creating "
                    + _prefix
                    + ": Size = "
                    + _size
                    + ", SizeMax = "
                    + _sizeMax
                    + ", SizeWarn = "
                    + _sizeWarn;
            _instance.initializationData().logger.trace(_instance.traceLevels().threadPoolCat, s);
        }

        try {
            for (int i = 0; i < _size; i++) {
                EventHandlerThread thread =
                    new EventHandlerThread(_threadPrefix + "-" + _threadIndex++);
                if (_hasPriority) {
                    thread.start(_priority);
                } else {
                    thread.start(Thread.NORM_PRIORITY);
                }
                _threads.add(thread);
            }
        } catch (RuntimeException ex) {
            String s = "cannot create thread for `" + _prefix + "':\n" + Ex.toString(ex);
            _instance.initializationData().logger.error(s);

            destroy();
            try {
                joinWithAllThreads();
            } catch (InterruptedException e) {
                throw new OperationInterruptedException(e);
            }
            throw ex;
        }
    }

    @SuppressWarnings({"nofinalizer", "deprecation"})
    @Override
    protected synchronized void finalize() throws Throwable {
        try {
            Assert.FinalizerAssert(_destroyed);
        } catch (Exception ex) {} finally {
            super.finalize();
        }
    }

    public synchronized void destroy() {
        if (_destroyed) {
            return;
        }

        _destroyed = true;
        
        // Stop selector thread
        _selectorRunning.set(false);
	// Wake up selector thread to notice shutdown
        _selector.wakeup(); 
        
        //_workQueue.destroy();
    }

    public synchronized void updateObservers() {
        for (EventHandlerThread thread : _threads) {
            thread.updateObserver();
        }
    }

    public synchronized void initialize(final EventHandler handler) {
        assert (!_destroyed);
        _selector.initialize(handler);

        handler.setReadyCallback(
            new ReadyCallback() {
                public void ready(int op, boolean value) {
                    if (_destroyed) {
                        return;
                    }
                    _selector.ready(handler, op, value);
                }
            });
    }

    public void register(EventHandler handler, int op) {
        update(handler, SocketOperation.None, op);
    }

    public synchronized void update(EventHandler handler, int remove, int add) {
        assert (!_destroyed);

        // Don't remove what needs to be added
        remove &= ~add;

        // Don't remove/add if already un-registered or registered
        remove = handler._registered & remove;
        add = ~handler._registered & add;
        if (remove == add) {
            return;
        }
        _selector.update(handler, remove, add);
    }

    public void unregister(EventHandler handler, int op) {
        update(handler, op, SocketOperation.None);
    }

    public synchronized boolean finish(EventHandler handler, boolean closeNow) {
        assert (!_destroyed);
        closeNow = _selector.finish(handler, closeNow);
        _workQueue.offer(new FinishedWorkItem(handler, !closeNow));
        return closeNow;
    }

    public void executeFromThisThread(RunnableThreadPoolWorkItem workItem) {
        if (_executor != null) {
            try {
                _executor.accept(workItem, workItem.getConnection());
            } catch (Exception ex) {
                if (_instance
                    .initializationData()
                    .properties
                    .getIcePropertyAsInt("Ice.Warn.Executor")
                    > 1) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    ex.printStackTrace(pw);
                    pw.flush();
                    _instance
                        .initializationData()
                        .logger
                        .warning("executor exception:\n" + sw.toString());
                }
            }
        } else {
            workItem.run();
        }
    }

    public void dispatch(RunnableThreadPoolWorkItem workItem) {
        if (_destroyed) {
            throw new CommunicatorDestroyedException();
        }
        _workQueue.offer(workItem);
    }

    public void joinWithAllThreads() throws InterruptedException {
        // Join selector thread first
        if (_selectorThread != null) {
            _selectorThread.join();
        }

        //
        // _threads is immutable after destroy() has been called,
        // therefore no synchronization is needed. (Synchronization wouldn't be possible here
        // anyway, because otherwise the other threads would never terminate.)
        //
        for (EventHandlerThread thread : _threads) {
            thread.join();
        }

        //
        // Destroy the selector
        //
        _selector.destroy();
    }

    //
    // Implement execute method from java.util.concurrent.Executor interface
    //
    @Override
    public void execute(Runnable command) {
        dispatch(
            new RunnableThreadPoolWorkItem() {
                @Override
                public void run() {
                    command.run();
                }
            });
    }

    // Dedicated selector thread for work queue pattern
    private final class SelectorThread extends Thread {
        public SelectorThread() {
            super(_threadPrefix + "-selector");
        }

        @Override
        public void run() {
            java.util.List<EventHandlerOpPair> handlers = new java.util.ArrayList<>();
            
            while (_selectorRunning.get()) {
                try {
                    handlers.clear();
                    _selector.startSelect();
                    _selector.select(_serverIdleTime);
                    _selector.finishSelect(handlers);
                    
                    // Queue all ready handlers as work items
                    for (EventHandlerOpPair handlerPair : handlers) {
                        if (handlerPair.handler != null) {
                            queueHandler(handlerPair.handler, handlerPair.op);
                        }
                    }
                } catch (Selector.TimeoutException ex) {
                    synchronized (ThreadPool.this) {
                        if (!_destroyed && _inUse == 0) {
                            _workQueue.offer(new ShutdownWorkItem());
                        }
                    }
                } catch (Exception ex) {
                    if (_selectorRunning.get()) { // Only log if we're still supposed to be running
                        String s = "exception in selector thread `" + _prefix + "':\n" + Ex.toString(ex);
                        _instance.initializationData().logger.error(s);
                        try {
                            Thread.sleep(100); // Brief pause to avoid tight loop
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
    }

    // Queue handler as work item
    private void queueHandler(EventHandler handler, int operation) {
        _workQueue.offer(new HandlerWorkItem(handler, operation));
    }

    // Worker thread run method for work queue pattern
    private void run(EventHandlerThread thread) {
        ThreadPoolCurrent current = new ThreadPoolCurrent(_instance, this, thread);
        
        while (true) {
            try {
                ThreadPoolWorkItem workItem = _workQueue.take();
                
                if (_destroyed) {
                    return;
                }
                
                try {
                    workItem.execute(current);
                } catch (DestroyedException ex) {
                    return;
                } catch (Exception ex) {
                    String s = "exception in `" + _prefix + "':\n" + Ex.toString(ex);
                    _instance.initializationData().logger.error(s);
                }
                
                current._handler = null;
                current.stream.reset();
                thread.setState(ThreadState.ThreadStateIdle);
                
            } catch (InterruptedException ex) {
                return;
            }
        }
    }

    // ioCompleted for work queue pattern. There is nothing to do here
    // really. Threads are all wiating on the blocking queue to consume
    // work
    void ioCompleted(ThreadPoolCurrent current) {
        current._ioCompleted =
            true; // Set the IO completed flag to specify that ioCompleted() has been called.

        current._thread.setState(ThreadState.ThreadStateInUseForUser);
    }


    private final Instance _instance;
    private final BiConsumer<Runnable, Connection> _executor;
    private boolean _destroyed;
    private final String _prefix;
    private final String _threadPrefix;
    private final Selector _selector;

    // Selector thread management
    private final AtomicBoolean _selectorRunning;
    private SelectorThread _selectorThread;
    private final BlockingQueue<ThreadPoolWorkItem> _workQueue;

    final class EventHandlerThread implements Runnable {
        EventHandlerThread(String name) {
            _name = name;
            _state = ThreadState.ThreadStateIdle;
            updateObserver();
        }

        public void updateObserver() {
            // Must be called with the thread pool mutex locked
            CommunicatorObserver obsv =
                _instance.initializationData().observer;
            if (obsv != null) {
                _observer = obsv.getThreadObserver(_prefix, _name, _state, _observer);
                if (_observer != null) {
                    _observer.attach();
                }
            }
        }

        public void setState(ThreadState s) {
            // Must be called with the thread pool mutex locked
            if (_observer != null) {
                if (_state != s) {
                    _observer.stateChanged(_state, s);
                }
            }
            _state = s;
        }

        public void join() throws InterruptedException {
            _thread.join();
        }

        public void start(int priority) {
            _thread = new Thread(null, this, _name, _stackSize);
            _thread.setPriority(priority);
            _thread.start();
        }

        @Override
        public void run() {
            if (_instance.initializationData().threadStart != null) {
                try {
                    _instance.initializationData().threadStart.run();
                } catch (Exception ex) {
                    String s = "threadStart method raised an unexpected exception in `";
                    s += _prefix + "' thread " + _name + ":\n" + Ex.toString(ex);
                    _instance.initializationData().logger.error(s);
                }
            }

            try {
                ThreadPool.this.run(this);
            } catch (Exception ex) {
                String s =
                    "exception in `" + _prefix + "' thread " + _name + ":\n" + Ex.toString(ex);
                _instance.initializationData().logger.error(s);
            }

            if (_observer != null) {
                _observer.detach();
            }

            if (_instance.initializationData().threadStop != null) {
                try {
                    _instance.initializationData().threadStop.run();
                } catch (Exception ex) {
                    String s = "threadStop method raised an unexpected exception in `";
                    s += _prefix + "' thread " + _name + ":\n" + Ex.toString(ex);
                    _instance.initializationData().logger.error(s);
                }
            }
        }

        Thread getThread() {
            return _thread;
        }

        private final String _name;
        private Thread _thread;
        private ThreadState _state;
        private ThreadObserver _observer;
    }

    private final int _size; // Number of threads that are pre-created.
    private final int _sizeIO; // Number of threads that can concurrently perform IO.
    private final int _sizeMax; // Maximum number of threads.
    private final int
        _sizeWarn; // If _inUse reaches _sizeWarn, a "low on threads" warning will be printed.
    private final boolean _serialize; // True if requests need to be serialized over the connection.
    private final int _priority;
    private final boolean _hasPriority;
    private final long _serverIdleTime;
    private final long _threadIdleTime;
    private final int _stackSize;

    private List<EventHandlerThread> _threads = new ArrayList<>();
    private int _threadIndex; // For assigning thread names.
    private int _inUse; // Number of threads that are currently in use.
}
