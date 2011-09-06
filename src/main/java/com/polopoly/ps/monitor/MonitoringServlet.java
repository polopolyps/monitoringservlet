package com.polopoly.ps.monitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import com.polopoly.application.ApplicationNotRunningException;
import com.polopoly.application.StandardApplication;
import com.polopoly.application.servlet.ApplicationServletUtil;
import com.polopoly.cm.ContentId;
import com.polopoly.cm.ExternalContentId;
import com.polopoly.cm.VersionedContentId;
import com.polopoly.cm.client.CMException;
import com.polopoly.cm.client.CmClient;
import com.polopoly.cm.client.ContentRead;
import com.polopoly.cm.client.EjbCmClient;
import com.polopoly.cm.client.impl.exceptions.EJBFinderException;
import com.polopoly.cm.client.impl.service2client.ClientCMServerServiceWrapper;
import com.polopoly.cm.policy.Policy;
import com.polopoly.cm.policy.PolicyCMServer;
import com.polopoly.cm.policy.PolicyCMServerBase;
import com.polopoly.cm.search.index.RemoteLuceneSearchUtil;
import com.polopoly.cm.search.index.RmiSearchClient;
import com.polopoly.cm.search.index.search.RemoteSearchService;
import com.polopoly.cm.search.index.search.SearchResult;
import com.polopoly.pear.ApplicationFactory;
import com.polopoly.pear.PolopolyApplication;
import com.polopoly.user.server.Caller;
import com.polopoly.user.server.UserId;

/**
 * This servlet is intended for monitoring whether the system is up and running.
 * If it returns a 500 response code, the system is probably down. See the
 * PARAMETER_* parameters for request parameters that may be sent to configure
 * its behavior. Note that the servlet imposes a load on the system on its own
 * and should therefore not be called too frequently (not more than every
 * minute).
 * 
 * @author andreasehrencrona
 */
public class MonitoringServlet extends HttpServlet implements
        MonitoringServletMBean {
    private static final Logger logger = Logger
            .getLogger(MonitoringServlet.class.getName());

    private static final String INPUT_TEMPLATE = "example.Monitor";

    private static final long DEFAULT_SEARCH_TIMEOUT = 5000;

    private static final long DEFAULT_COMMIT_TIMEOUT = 2000;

    /**
     * This is intentionally very low. In some circumstances, there is no way to
     * check there is free memory but to attempt to allocate it and we don't
     * want to stress the garbage collector if memory is already tight.
     */
    private static final long DEFAULT_MEMORY_LIMIT = 5 * 1024 * 1024;

    /**
     * The request parameter whether to do a commit. Call with "false" as value
     * to avoid commit.
     */
    private static final String PARAMETER_COMMIT_REQUESTED = "commit";

    /**
     * The request parameter whether to do a search for an article in
     * DefaultIndex. Call with "false" as value to avoid search.
     */
    private static final String PARAMETER_SEARCH_ARTICLE_REQUESTED = "searcharticle";

    /**
     * The request parameter whether to check the amount of free memory. Call
     * with "false" as value to avoid checking.
     */
    private static final String PARAMETER_MEMORY_REQUESTED = "memory";

    /**
     * The request parameter whether to check that CM is connected. Call with
     * "false" as value to avoid checking.
     */
    private static final String PARAMETER_CONNECTED_REQUESTED = "connected";

    /**
     * The request parameter for the default value of whether to perform a
     * check. Defaults to "true". If called with value "false", no checks will
     * be performed except for those explicitly set to the "true" using the
     * PARAMETER_* parameters above.
     */
    private static final String PARAMETER_ALL_REQUESTED = "all";

    /**
     * The request parameter to overwrite the default search timeout. The value
     * is the number of ms a search may take before it is considered to have
     * failed.
     */
    private static final String PARAMETER_SEARCH_TIMEOUT = "searchtimeout";

    /**
     * The request parameter to overwrite the default commit timeout. The value
     * is the number of ms content creation and commit may take before it is
     * considered to have failed.
     */
    private static final String PARAMETER_COMMIT_TIMEOUT = "committimeout";

    /**
     * The request parameter to overwrite the default free memory limit. The
     * value is the number of Mb of free memory needed for the servlet to
     * consider free memory test passing. Don't set this too high as the servlet
     * will actually try to allocate this amount of memory in some circumstances
     * (no more than 10 Mb).
     */
    private static final String PARAMETER_FREE_MEMORY_LIMIT_IN_MB = "requiredmemory";

    private static final int MAX_RUNNING_SERVLETS = 2;

    private static String externalId;

    private static ContentId knownArticle;

    private static int running = 0;

    private StandardApplication application = null;

    private PolicyCMServer server;

    private long latestCommit;

    private long latestCommitResult;

    @Override
    public void init() throws ServletException {
        super.init();

        registerMBean();
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        running++;

        try {
            if (running > MAX_RUNNING_SERVLETS) {
                throw new TestFailedException("There were " + running
                        + " concurrent monitoring requests.");
            }

            Map<String, String> results = new HashMap<String, String>();

            initialize();

            if (isRequested(req, PARAMETER_MEMORY_REQUESTED)) {
                results.put(PARAMETER_MEMORY_REQUESTED,
                        testMemory(getFreeMemoryLimit(req)));
            }

            if (isRequested(req, PARAMETER_CONNECTED_REQUESTED)) {
                results.put(PARAMETER_CONNECTED_REQUESTED,
                        testConnected(server));
            }

            if (isRequested(req, PARAMETER_SEARCH_ARTICLE_REQUESTED)) {
                results.put(PARAMETER_SEARCH_ARTICLE_REQUESTED, "OK - "
                        + testSearchArticle(application, getSearchTimeout(req))
                        + " ms");
            }

            boolean commitRequested = isRequested(req,
                    PARAMETER_COMMIT_REQUESTED);

            if (commitRequested) {
                results.put(PARAMETER_COMMIT_REQUESTED, "OK - "
                        + testCommit(server, getCommitTimeout(req)) + " ms");
            }

            PrintWriter writer = resp.getWriter();

            writer
                    .print("<html><body><head><title>System Check Successful</title>"
                            + "</head><body><h2>System Check Successful</h2>"
                            + "<ul>");

            for (Entry<String, String> entry : results.entrySet()) {
                writer.println("<li><b>" + entry.getKey() + "</b>: "
                        + entry.getValue() + "</li>");
            }

            writer.print("</ul></body></html>");

            StringBuffer info = new StringBuffer(1000);

            boolean first = true;

            for (Entry<String, String> entry : results.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    info.append(", ");
                }

                info.append(entry.getKey() + ": " + entry.getValue());
            }

            logger.log(Level.INFO, "Monitoring servlet completed ok: " + info);
        } catch (TestFailedException e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getOutputStream().print(e.getMessage());
        } catch (ApplicationNotRunningException e) {
            throw new ServletException(e);
        } catch (IllegalArgumentException e) {
            throw new ServletException(e);
        } finally {
            running--;
        }
    }

    private void initialize() {
        if (server == null) {
            try {
                // this will throw an exception in the front app (using PAF)
                PolopolyApplication pearApp = (PolopolyApplication) ApplicationFactory
                        .getApplication();

                // use PEAR to retrieve application
                server = pearApp.getPolicyCMServer();
            } catch (IllegalStateException ise) {
                // use PAF to retrieve application
                application = (StandardApplication) ApplicationServletUtil
                        .getApplication(getServletContext());

                CmClient cmClient = (CmClient) application
                        .getApplicationComponent(EjbCmClient.DEFAULT_COMPOUND_NAME);

                server = cmClient.getPolicyCMServer();
            }
        }

        if (server.getCurrentCaller() == null) {
            server.setCurrentCaller(new Caller(new UserId("test")));
        }
    }

    private String testMemory(long freeMemoryLimit) throws TestFailedException {
        Runtime runtime = Runtime.getRuntime();

        long allocatable = runtime.maxMemory() - runtime.totalMemory();

        long freeMemory = runtime.freeMemory();

        if (freeMemory < freeMemoryLimit) {
            // first, see if a GC helps freeing up memory
            System.gc();

            freeMemory = runtime.freeMemory();

            if (freeMemory < freeMemoryLimit) {
                // if we're still out of free heap, check if the JVM could
                // allocate more total memory
                if (freeMemory + allocatable < freeMemoryLimit) {
                    throw new TestFailedException(
                            "There is only "
                                    + (freeMemory / (1024 * 1024))
                                    + " Mb of free memory and only "
                                    + ((runtime.maxMemory() - runtime
                                            .totalMemory()) / (1024 * 1024))
                                    + " Mb more can be allocated by the JVM.");
                }

                // more memory can be perhaps allocated (provided there is free
                // memory in the system at an OS level)
                // but the JVM has not grabbed it yet. there is no way to check
                // whether we are actually out
                // of memory or the JVM could allocate more but to actually try
                // to allocate it.
                try {
                    @SuppressWarnings("unused")
                    byte[] foo = new byte[(int) freeMemoryLimit / 2];
                } catch (OutOfMemoryError e) {
                    throw new TestFailedException("Could not allocate "
                            + (freeMemoryLimit / (2 * 1024 * 1024))
                            + " Mb of memory.");
                }
            }
        }

        return "OK - " + (freeMemory / (1024 * 1024)) + " Mb free, "
                + (allocatable / (1024 * 1024) + " Mb more allocatable");
    }

    private String testConnected(PolicyCMServer server) throws ServletException {
        if (!((ClientCMServerServiceWrapper) ((PolicyCMServerBase) server)
                .getCMServer()).isCurrentlyConnected()) {
            throw new ServletException("CM server is not connected.");
        }

        return "CONNECTED";
    }

    private long testSearchArticle(StandardApplication application,
            long searchTimeout) throws TestFailedException {
        try {
            if (knownArticle == null) {
                TermQuery query = new TermQuery(new Term("major", "Article"));

                knownArticle = search(application, "DefaultIndex", query);
            }

            long t = System.currentTimeMillis();

            Query query = new TermQuery(new Term("contentid", knownArticle
                    .getContentId().getContentIdString()));

            search(application, "DefaultIndex", query);

            long searchTime = System.currentTimeMillis() - t;

            if (searchTime > searchTimeout) {
                throw new TestFailedException("Searching took " + searchTime
                        + " ms.");
            }

            return searchTime;
        } catch (TestFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new TestFailedException(e);
        }
    }

    private ContentId search(StandardApplication application, String index,
            Query query) throws TestFailedException {
        try {
            RemoteSearchService searchService;

            if (application != null) {
                // PAF
                RmiSearchClient searchClient = (RmiSearchClient) application
                        .getApplicationComponent(RmiSearchClient.DEFAULT_COMPOUND_NAME);

                searchService = searchClient.getRemoteSearchService(index);
            } else {
                // PEAR
                searchService = RemoteLuceneSearchUtil.getInstance(index)
                        .getSearchService();
            }

            if (searchService == null) {
                throw new TestFailedException(
                        "Could not get search service for " + index
                                + ". getSearchService returned null.");
            }

            SearchResult result = searchService
                    .search(query, null, null, 10, 0);

            if (result == null) {
                throw new TestFailedException("Got no search result in "
                        + index + ".");
            }

            if (result.getTotalNumberOfHits() <= 0) {
                throw new TestFailedException("Got no search results in "
                        + index + " while searching.");
            }

            return (ContentId) result.getContentIds().get(0);
        } catch (TestFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new TestFailedException(e);
        }

    }

    private long testCommit(PolicyCMServer server, long commitTimeout)
            throws TestFailedException {
        Caller oldCaller = server.getCurrentCaller();

        try {
            server.setCurrentCaller(new Caller(new UserId("12345")));

            /**
             * We synchronize so multiple requests on the same client cannot
             * stumble over each other. We use different content objects for
             * each client so we don't have locking problems between clients.
             */
            synchronized (getClass()) {
                long t = System.currentTimeMillis();

                // this is since the mbeans may be called in any order and
                // change
                // list required commit to have been called.
                if (t - latestCommit < 500) {
                    return latestCommitResult;
                }

                ContentRead content;
                Policy policy;

                try {
                    content = server.getContent(new ExternalContentId(
                            getExternalId()));
                    policy = server
                            .createContentVersion(content.getContentId());
                } catch (EJBFinderException e) {
                    VersionedContentId templateId = server
                            .findContentIdByExternalId(new ExternalContentId(
                                    INPUT_TEMPLATE));

                    if (templateId == null) {
                        throw new TestFailedException("The input template "
                                + INPUT_TEMPLATE
                                + " could not be found. Has it been imported?");
                    }

                    policy = server.createContent(17, templateId);
                    policy.getContent().setName("Monitoring Servlet Content");
                    policy.getContent().setExternalId(getExternalId());
                }

                policy.getContent().commit();

                long commitTime = System.currentTimeMillis() - t;

                if (commitTime > commitTimeout) {
                    throw new TestFailedException(
                            "Creating a new content object and committing took "
                                    + commitTime + " ms.");
                }

                latestCommit = t;
                latestCommitResult = commitTime;

                return commitTime;
            }
        } catch (CMException e) {
            throw new TestFailedException(e);
        } finally {
            server.setCurrentCaller(oldCaller);
        }
    }

    /**
     * Returns the required amount of free memory in bytes (less than this and
     * the servlet will fail).
     */
    private static long getFreeMemoryLimit(HttpServletRequest req) {
        String limit = req.getParameter(PARAMETER_FREE_MEMORY_LIMIT_IN_MB);

        if (limit != null) {
            try {
                return (long) 1024 * 1024 * Integer.parseInt(limit);
            } catch (NumberFormatException e) {
                logger.log(Level.WARNING, "Memory limit \"" + limit
                        + "\" was not a number.");
            }
        }

        return DEFAULT_MEMORY_LIMIT;
    }

    /**
     * Returns the number of ms a search may take before it is considered to
     * have timed out (in which case the servlet will fail).
     */
    private static long getSearchTimeout(HttpServletRequest req) {
        String timeout = req.getParameter(PARAMETER_SEARCH_TIMEOUT);

        if (timeout != null) {
            try {
                return Integer.parseInt(timeout);
            } catch (NumberFormatException e) {
                logger.log(Level.WARNING, "Timeout \"" + timeout
                        + "\" was not a number.");
            }
        }

        return DEFAULT_SEARCH_TIMEOUT;
    }

    /**
     * Returns the number of ms creating a new content and committing it may
     * take before it is considered to have timed out (in which case the servlet
     * will fail).
     */
    private static long getCommitTimeout(HttpServletRequest req) {
        String timeout = req.getParameter(PARAMETER_COMMIT_TIMEOUT);

        if (timeout != null) {
            try {
                return Integer.parseInt(timeout);
            } catch (NumberFormatException e) {
                logger.log(Level.WARNING, "Timeout \"" + timeout
                        + "\" was not a number.");
            }
        }

        return DEFAULT_COMMIT_TIMEOUT;
    }

    private static boolean isRequested(HttpServletRequest req, String parameter) {
        /*
         * Either "all" is false, in which case we the parameter for the
         * specific test needs to be true, or "all" is not false (and thus true)
         * and the parameter for the specified test is not false.
         */
        return ("false".equals(req.getParameter(PARAMETER_ALL_REQUESTED)) && "true"
                .equals(req.getParameter(parameter)))
                || (!"false".equals(req.getParameter(PARAMETER_ALL_REQUESTED)) && !"false"
                        .equals(req.getParameter(parameter)));
    }

    /**
     * Returns an ID that is supposed to unique for each servlet container
     * serving as Polopoly client serving as external ID for the content object
     * written by the monitoring servlet.
     */
    private String getExternalId() {
        if (externalId == null) {
            String applicationName = "n/a";

            try {
                try {
                    if (ApplicationFactory.getContext() != null) {
                        applicationName = ApplicationFactory.getApplication()
                                .getName();
                    }
                } catch (IllegalStateException e) {
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not get application name: "
                        + e.getMessage(), e);
            }

            String host = "n/a";

            try {
                host = InetAddress.getLocalHost().getCanonicalHostName();
            } catch (Exception e) {
                String fallback = Integer
                        .toString(new Random().nextInt(900000) + 100000);

                logger.log(Level.WARNING,
                        "Could not get host name. Using fallback '" + fallback
                                + "'.", e);

                return fallback;
            }

            externalId = "example.monitor." + applicationName + "-" + host;
        }

        return externalId;
    }

    public long getCommitTime() {
        initialize();

        return testCommit(server, DEFAULT_COMMIT_TIMEOUT);
    }

    public long getSearchArticleTime() {
        initialize();

        if (application == null) {
            throw new TestFailedException(
                    "Not using PAF; could not retrieve search client.");
        }

        return testSearchArticle(application, DEFAULT_SEARCH_TIMEOUT);
    }

    public boolean isConnected() {
        initialize();

        try {
            testConnected(server);

            return true;
        } catch (ServletException e) {
            return false;
        }
    }

    private void registerMBean() {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        try {
            ObjectName name = new ObjectName(getMBeanName());

            if (!alreadyRegistered(mbs)) {
                mbs.registerMBean(this, name);
            } else {
                logger.log(Level.SEVERE,
                        "Attempt to register monitoring MBean twice.");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "While registering monitoring servlet MBean : "
                            + e.getMessage(), e);
        }
    }

    private String getMBeanName() {
        initialize();

        return getClass().getPackage().getName()
                + ":"
                + (application != null ? "app=" + application.getName() + ", "
                        : "") + "type=" + getClass().getSimpleName();
    }

    private boolean alreadyRegistered(MBeanServer mbs) {
        try {
            return mbs.getObjectInstance(new ObjectName(getMBeanName())) != null;
        } catch (InstanceNotFoundException e) {
            return false;
        } catch (MalformedObjectNameException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
            return true;
        }
    }
}
