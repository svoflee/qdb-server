/*
 * Copyright 2013 David Tinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.qdb.server.output;

import io.qdb.buffer.MessageBuffer;
import io.qdb.buffer.MessageCursor;
import io.qdb.server.ExpectedIOException;
import io.qdb.server.controller.JsonService;
import io.qdb.server.databind.DataBinder;
import io.qdb.server.model.Database;
import io.qdb.server.model.Output;
import io.qdb.server.model.Queue;
import io.qdb.server.queue.QueueManager;
import io.qdb.server.repo.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Watches a queue for new messages and processes them.
 */
public class OutputJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(OutputJob.class);

    private final OutputManager outputManager;
    private final OutputHandlerFactory handlerFactory;
    private final QueueManager queueManager;
    private final Repository repo;
    private final JsonService jsonService;
    private final String oid;

    private Thread thread;
    private String outputPath;
    private Output output;
    private int errorCount;
    private boolean stopFlag;

    public OutputJob(OutputManager outputManager, OutputHandlerFactory handlerFactory, QueueManager queueManager,
                     Repository repo, JsonService jsonService, String oid) {
        this.outputManager = outputManager;
        this.handlerFactory = handlerFactory;
        this.queueManager = queueManager;
        this.repo = repo;
        this.jsonService = jsonService;
        this.oid = oid;
    }

    public String getOid() {
        return oid;
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        try {
            mainLoop();
        } catch (Exception x) {
            logError(x);
        } finally {
            if (log.isDebugEnabled()) log.debug(this + " exit");
            outputManager.onOutputJobExit(this);
        }
    }

    private void logError(Throwable t) {
        String msg;
        if (t instanceof ExpectedIOException) {
            msg = t.getMessage();
            if (msg == null) msg = t.toString();
        } else {
            msg = t.toString();
        }
        log.error(this + ": " + msg, t instanceof ExpectedIOException ? null : t);
    }

    private void mainLoop() throws Exception {
        while (!isStopFlag()) {

            output = repo.findOutput(oid);
            if (output == null) {
                if (log.isDebugEnabled()) log.debug("Output [" + oid + "] does not exist");
                return;
            }
            if (!output.isEnabled()) return;

            Queue q = repo.findQueue(output.getQueue());
            if (q == null) {
                if (log.isDebugEnabled()) log.debug("Queue [" + output.getQueue() + "] does not exist");
                return;
            }

            Database db = repo.findDatabase(q.getDatabase());
            if (db == null) {
                if (log.isDebugEnabled()) log.debug("Database [" + q.getDatabase() + "] does not exist");
                return;
            }

            outputPath = toPath(db, q, output);

            OutputHandler handler;
            try {
                handler = handlerFactory.createHandler(output.getType());
            } catch (IllegalArgumentException e) {
                log.error("Error creating handler for " + outputPath + ": " + e.getMessage(), e);
                return;
            }

            boolean initOk = false;
            try {
                // give the handler clones so modifications to q or output don't cause trouble
                Output oc = output.deepCopy();
                Queue qc = q.deepCopy();
                Map<String, Object> p = oc.getParams();
                if (p != null) new DataBinder(jsonService).ignoreInvalidFields(true).bind(p, handler).check();
                handler.init(qc, oc, outputPath);
                initOk = true;
            } catch (IllegalArgumentException e) {
                log.error(this + ": " + e.getMessage());
                return;
            } catch (Exception e) {
                logError(e);
                ++errorCount;
            }

            try {
                if (initOk) {
                    MessageBuffer buffer = queueManager.getBuffer(q);
                    if (buffer == null) {   // we might be busy starting up or something
                        if (log.isDebugEnabled()) log.debug("Queue [" + q.getId() + "] does not have a buffer");
                        ++errorCount;
                    } else {
                        try {
                            processMessages(buffer, handler);
                        } catch (Exception e) {
                            ++errorCount;
                            logError(e);
                        }
                    }
                }
            } finally {
                try {
                    handler.close();
                } catch (IOException e) {
                    log.error(this + ": Error closing handler: " + e, e);
                }
            }

            // todo use backoff policy from output
            int sleepMs = errorCount * 1000;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    /**
     * Feed messages to our handler until we are closed or our output is changed by someone else.
     */
    public void processMessages(MessageBuffer buffer, OutputHandler handler) throws Exception {
        if (log.isDebugEnabled()) log.debug(outputPath + ": processing messages");
        MessageCursor cursor = null;
        try {
            long atId = output.getAtId();
            cursor = atId < 0 ? buffer.cursorByTimestamp(output.getAt()) : buffer.cursor(atId);

            long completedId = atId;
            long timestamp = 0;
            long lastUpdate = System.currentTimeMillis();
            int updateIntervalMs = output.getUpdateIntervalMs();

            boolean exitLoop = false;
            while (!exitLoop && !isStopFlag()) {
                boolean haveMsg;
                try {
                    haveMsg = cursor.next(1000);
                } catch (IOException e) {
                    haveMsg = false;
                    exitLoop = true;
                    logError(e);
                } catch (InterruptedException e) {
                    haveMsg = false;
                    exitLoop = true;
                }
                if (haveMsg) {
                    try {
                        long currentId = cursor.getId();
                        completedId = handler.processMessage(currentId, cursor.getRoutingKey(),
                                timestamp = cursor.getTimestamp(), cursor.getPayload());
                        if (completedId == currentId) completedId = cursor.getNextId();
                        else ++completedId;
                        errorCount = 0; // we successfully processed a message
                    } catch (Exception e) {
                        exitLoop = true;
                        ++errorCount;
                        logError(e);
                    }
                }

                Output o = repo.findOutput(oid);
                if (o != output) {
                    exitLoop = true; // output has been changed by someone else
                    errorCount = 0;
                }

                if (completedId != atId && (exitLoop || updateIntervalMs <= 0
                        || System.currentTimeMillis() - lastUpdate >= updateIntervalMs)) {
                    synchronized (repo) {
                        o = repo.findOutput(oid);
                        // don't record our progress if we are now supposed to be processing from a different point in buffer
                        if (o.getAtId() != output.getAtId() || o.getAt() != output.getAt()) break;
                        output = o.deepCopy();
                        output.setAt(timestamp);
                        handler.updateOutput(output);
                        output.setAtId(completedId);
                        repo.updateOutput(output);
                        atId = completedId;
                        lastUpdate = System.currentTimeMillis();
                    }
                }
            }
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (IOException e) {
                    log.error(this + ": Error closing cursor: " + e);
                }
            }
        }
    }

    public void outputChanged(Output o) {
        // if o is the same object as our current Output instance then we made the change so don't stop
        if (o != output && thread != null) thread.interrupt();
    }

    public synchronized void stop() {
        stopFlag = true;
        if (thread != null) thread.interrupt();
    }

    private synchronized boolean isStopFlag() {
        return stopFlag;
    }

    /**
     * Create user friendly identifier for the output for error messages and so on.
     */
    private String toPath(Database db, Queue q, Output o) {
        StringBuilder b = new StringBuilder();
        String dbId = db.getId();
        if (!"default".equals(dbId)) b.append("/db/").append(dbId);
        String s = db.getQueueForQid(q.getId());
        if (s != null) {
            b.append("/q/").append(s);
            s = q.getOutputForOid(o.getId());
            if (s != null) {
                return b.append("/out/").append(s).toString();
            }
        }
        return o.toString();
    }

    @Override
    public String toString() {
        return outputPath == null ? "output[" + oid + "]" : outputPath;
    }
}
