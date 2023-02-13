/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jenkinsci.test.acceptance.server;

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;

import com.cloudbees.sdk.extensibility.Extension;
import com.google.inject.Injector;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Mode;
import hudson.remoting.ChannelBuilder;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.test.acceptance.controller.IJenkinsController;
import org.jenkinsci.test.acceptance.controller.JenkinsController;
import org.jenkinsci.test.acceptance.controller.LocalController.LocalFactoryImpl;
import org.jenkinsci.test.acceptance.log.LogListenable;
import org.jenkinsci.test.acceptance.log.LogListener;
import org.jenkinsci.test.acceptance.log.LogSplitter;

import static java.lang.System.*;

/**
 * {@link JenkinsController} that talks to {@link JenkinsControllerPoolProcess} over Unix domain socket.
 *
 * @author Kohsuke Kawaguchi
 */
public class PooledJenkinsController extends JenkinsController implements LogListenable {
    private URL url;
    private final File socket;
    private UnixSocketChannel conn;
    private final LogSplitter splitter = new LogSplitter();
    private Channel channel;
    private IJenkinsController controller;
    private final List<byte[]> toUnpack = new LinkedList<>();

    public PooledJenkinsController(Injector i, File socket) {
        super(i);
        this.socket = socket;
    }

    @Override
    public void addLogListener(LogListener l) {
        splitter.addLogListener(l);
    }

    @Override
    public void removeLogListener(LogListener l) {
        splitter.removeLogListener(l);
    }

    private boolean connect() throws IOException {
        if (conn != null) return false;

        System.out.println("Requesting jut instance using socket " + socket.getAbsolutePath());
        UnixSocketAddress address = new UnixSocketAddress(socket);
        conn = UnixSocketChannel.open(address);

        channel = new ChannelBuilder("JenkinsPool", Executors.newCachedThreadPool())
                .withMode(Mode.BINARY)
                .build(ChannelStream.in(conn), ChannelStream.out(conn));

        try {
            controller = (IJenkinsController) channel.waitForRemoteProperty("controller");
            controller.start();
            url = controller.getUrl();

            if (!isQuite) {
                splitter.addLogListener(getLogPrinter());
            }

            final LogListener l = channel.export(LogListener.class, splitter);
            channel.call(new InstallLogger(controller, l));

            for (byte[] content : toUnpack) {
                controller.populateJenkinsHome(content, false);
            }
            toUnpack.clear();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        return true;
    }

    @Override
    public void startNow() throws IOException {
        connect();
    }

    @Override
    public void stopNow() throws IOException {
        controller.stop();
    }

    @Override
    public void populateJenkinsHome(byte[] template, boolean clean) throws IOException {
        if (controller != null) {
            controller.populateJenkinsHome(template, clean);
        } else {
            if (clean) {
                throw new UnsupportedOperationException("clean mode unsupported for now");
            }
            toUnpack.add(template);
        }
    }

    @Override
    public URL getUrl() {
        if (url == null) throw new IllegalStateException("This controller has not been started");
        return url;
    }

    @Override
    public void tearDown() throws IOException {
        channel.close();
        try {
            channel.join(3000);
        } catch (InterruptedException e) {
            throw new IOException(e);
        } finally {
            if (conn != null) conn.close();
            conn = null;
        }
    }

    @Override
    public void diagnose(Throwable cause) {
        // TODO: Report jenkins log
        cause.printStackTrace(out);
        if (getenv("INTERACTIVE") != null && getenv("INTERACTIVE").equals("true")) {
            out.println("Commencing interactive debugging. Browser session was kept open.");
            out.println("Press return to proceed.");
            try {
                in.read();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Extension
    public static class FactoryImpl extends LocalFactoryImpl {
        @Inject
        Injector i;

        @Override
        public String getId() {
            return "pool";
        }

        @Override
        public JenkinsController create() {
            return i.getInstance(PooledJenkinsController.class);
        }
    }

    /**
     * Runs on the pool server to install logger.
     */
    private static class InstallLogger implements Callable<Void, IOException> {
        private final IJenkinsController controller;
        private final LogListener l;

        private InstallLogger(IJenkinsController controller, LogListener l) {
            this.controller = controller;
            this.l = l;
        }

        @Override
        public Void call() throws IOException {
            if (controller instanceof LogListenable) {
                LogListenable ll = (LogListenable) controller;
                ll.addLogListener(l);
            }
            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {}

        private static final long serialVersionUID = 1L;
    }
}
