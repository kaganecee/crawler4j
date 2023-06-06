package edu.uci.ics.crawler4j.frontier;

import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;

import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.url.WebURL;

public class Frontier {
    protected static final Logger logger = LoggerFactory.getLogger(Frontier.class);

    private static final String DATABASE_NAME = "PendingURLsDB";
    private static final int IN_PROCESS_RESCHEDULE_BATCH_SIZE = 100;
    private final CrawlConfig config;
    protected WorkQueues workQueues;

    protected InProcessPagesDB inProcessPages;

    protected final Object mutex = new Object();
    protected final Object waitingList = new Object();

    protected boolean isFinished = false;

    protected long scheduledPages;

    protected Counters counters;

    private Stack<WebURL> stack;

    public Frontier(Environment env, CrawlConfig config) {
        this.config = config;
        this.counters = new Counters(env, config);
        try {
            workQueues = new WorkQueues(env, DATABASE_NAME, config.isResumableCrawling());
            if (config.isResumableCrawling()) {
                scheduledPages = counters.getValue(Counters.ReservedCounterNames.SCHEDULED_PAGES);
                inProcessPages = new InProcessPagesDB(env);
                long numPreviouslyInProcessPages = inProcessPages.getLength();
                if (numPreviouslyInProcessPages > 0) {
                    logger.info("Rescheduling {} URLs from previous crawl.", numPreviouslyInProcessPages);
                    scheduledPages -= numPreviouslyInProcessPages;

                    Stack<WebURL> urls = inProcessPages.get(IN_PROCESS_RESCHEDULE_BATCH_SIZE);
                    while (!urls.isEmpty()) {
                        scheduleAll(urls);
                        inProcessPages.delete(urls.size());
                        urls = inProcessPages.get(IN_PROCESS_RESCHEDULE_BATCH_SIZE);
                    }
                }
            } else {
                inProcessPages = null;
                scheduledPages = 0;
            }
            
            stack = new Stack<>();
        } catch (DatabaseException e) {
            logger.error("Error while initializing the Frontier", e);
            workQueues = null;
        }
    }

    public void scheduleAll(Stack<WebURL> urls) {
        int maxPagesToFetch = config.getMaxPagesToFetch();
        synchronized (mutex) {
            int newScheduledPage = 0;
            while (!urls.isEmpty()) {
                if ((maxPagesToFetch > 0) && ((scheduledPages + newScheduledPage) >= maxPagesToFetch)) {
                    break;
                }

                try {
                    workQueues.put(urls.pop());
                    newScheduledPage++;
                } catch (DatabaseException e) {
                    logger.error("Error while putting the url in the work queue", e);
                }
            }
            if (newScheduledPage > 0) {
                scheduledPages += newScheduledPage;
                counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES, newScheduledPage);
            }
            synchronized (waitingList) {
                waitingList.notifyAll();
            }
        }
    }

    public void schedule(WebURL url) {
        int maxPagesToFetch = config.getMaxPagesToFetch();
        synchronized (mutex) {
            try {
                if (maxPagesToFetch < 0 || scheduledPages < maxPagesToFetch) {
                    workQueues.put(url);
                    scheduledPages++;
                    counters.increment(Counters.ReservedCounterNames.SCHEDULED_PAGES);
                }
            } catch (DatabaseException e) {
                logger.error("Error while putting the url in the work queue", e);
            }
        }
    }

    public void getNextURLs(int max, Stack<WebURL> result) {
        while (true) {
            synchronized (mutex) {
                if (isFinished) {
                    return;
                }
                try {
                    Stack<WebURL> curResults = workQueues.get(max);
                    workQueues.delete(curResults.size());
                    if (inProcessPages != null) {
                        for (WebURL curPage : curResults) {
                            inProcessPages.put(curPage);
                        }
                    }
                    while (!curResults.isEmpty()) {
                        result.push(curResults.pop());
                    }
                } catch (DatabaseException e) {
                    logger.error("Error while getting next urls", e);
                }

                if (!result.isEmpty()) {
                    return;
                }
            }

            try {
                synchronized (waitingList) {
                    waitingList.wait();
                }
            } catch (InterruptedException ignored) {
                // Do nothing
            }
            if (isFinished) {
                return;
            }
        }
    }

    public void setProcessed(WebURL webURL) {
        counters.increment(Counters.ReservedCounterNames.PROCESSED_PAGES);
        if (inProcessPages != null) {
            if (!inProcessPages.removeURL(webURL)) {
                logger.warn("Could not remove: {} from list of processed pages.", webURL.getURL());
            }
        }
    }

    public long getQueueLength() {
        return workQueues.getLength();
    }

    public long getNumberOfAssignedPages() {
        if (inProcessPages != null) {
            return inProcessPages.getLength();
        } else {
            return 0;
        }
    }

    public long getNumberOfProcessedPages() {
        return counters.getValue(Counters.ReservedCounterNames.PROCESSED_PAGES);
    }

    public long getNumberOfScheduledPages() {
        return counters.getValue(Counters.ReservedCounterNames.SCHEDULED_PAGES);
    }

    public boolean isFinished() {
        return isFinished;
    }

    public void close() {
        workQueues.close();
        counters.close();
        if (inProcessPages != null) {
            inProcessPages.close();
        }
    }

    public void finish() {
        isFinished = true;
        synchronized (waitingList) {
            waitingList.notifyAll();
        }
    }
}