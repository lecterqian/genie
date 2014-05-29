/*
 *
 *  Copyright 2013 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.server.jobmanager.impl;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.configuration.AbstractConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.config.ConfigurationManager;
import com.netflix.genie.common.exceptions.CloudServiceException;
import com.netflix.genie.common.model.Job;
import com.netflix.genie.common.model.Types;
import com.netflix.genie.common.model.Types.JobStatus;
import com.netflix.genie.common.model.Types.SubprocessStatus;
import com.netflix.genie.server.jobmanager.JobManagerFactory;
import com.netflix.genie.server.metrics.GenieNodeStatistics;
import com.netflix.genie.server.persistence.PersistenceManager;
import com.netflix.genie.server.util.NetUtil;

/**
 * The monitor thread that gets launched for each job.
 *
 * @author skrishnan
 * @author amsharma
 */
public class JobMonitor extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(JobMonitor.class);

    private final Job job;
    private final PersistenceManager<Job> pm;

    // interval to poll for process status
    private static final int JOB_WAIT_TIME_MS = 5000;

    // interval to check status, and update in database if needed
    private static final int JOB_UPDATE_TIME_MS = 60000;

    // last updated time in DB
    private long lastUpdatedTimeMS;

    // the handle to the process for the running job
    private final Process proc;

    // the working directory for this job
    private final String workingDir;

    // the stdout for this job
    private File stdOutFile;

    // max specified stdout size
    private final Long maxStdoutSize;

    // whether this job has been terminated by the monitor thread
    private boolean terminated = false;

    // Config Instance to get all properties
    private final AbstractConfiguration config;

    /**
     * Initialize this monitor thread with the process for the running job.
     *
     * @param ji the job info object for this running job
     * @param workingDir the working directory for this job
     * @param proc process handle for running job
     */
    public JobMonitor(Job ji, String workingDir, Process proc) {
        this.job = ji;
        this.config = ConfigurationManager.getConfigInstance();
        this.workingDir = workingDir;
        if (this.workingDir != null) {
            stdOutFile = new File(this.workingDir + File.separator + "stdout.log");
        }
        this.proc = proc;
        this.pm = new PersistenceManager<Job>();
        this.maxStdoutSize = config.getLong("netflix.genie.job.max.stdout.size", null);
    }

    /**
     * Is the job running?
     *
     * @return true if job is running, false otherwise
     */
    private boolean isRunning() {
        try {
            proc.exitValue();
        } catch (IllegalThreadStateException e) {
            return true;
        }
        return false;
    }

    /**
     * Check if it is time to update the job status.
     *
     * @return true if job hasn't been updated for configured time, false
     * otherwise
     */
    private boolean shouldUpdateJob() {
        long curTimeMS = System.currentTimeMillis();
        long timeSinceStartMS = curTimeMS - lastUpdatedTimeMS;

        return timeSinceStartMS >= JOB_UPDATE_TIME_MS;
    }

    /**
     * Wait until the job finishes, and then return exit code. Also ensure that
     * stdout is within the limit (if specified), and update DB status
     * periodically (as RUNNING).
     *
     * @return exit code for the job after it finishes
     */
    private int waitForExit() {
        lastUpdatedTimeMS = System.currentTimeMillis();
        while (isRunning()) {
            try {
                Thread.sleep(JOB_WAIT_TIME_MS);
            } catch (InterruptedException e) {
                LOG.error("Exception while waiting for job " + job.getJobID()
                        + " to finish", e);
                // move on
            }

            // update status only in JOB_UPDATE_TIME_MS intervals
            if (shouldUpdateJob()) {
                LOG.debug("Updating db for job: " + job.getJobID());

                lastUpdatedTimeMS = System.currentTimeMillis();
                job.setJobStatus(JobStatus.RUNNING, "Job is running");
                job.setUpdateTime(lastUpdatedTimeMS);

                // only update DB if it is not KILLED already
                ReentrantReadWriteLock rwl = PersistenceManager.getDbLock();
                try {
                    rwl.writeLock().lock();
                    Job dbJI = pm.getEntity(job.getJobID(),
                            Job.class);
                    if ((dbJI.getStatus() != null)
                            && dbJI.getStatus() != JobStatus.KILLED) {
                        pm.updateEntity(job);
                    }
                } catch (Exception e) {
                    LOG.error(
                            "Exception while trying to update status for job: "
                            + job.getJobID(), e);
                    // continue - as we shouldn't terminate this thread until
                    // job is running
                } finally {
                    // ensure that we always unlock
                    if (rwl.writeLock().isHeldByCurrentThread()) {
                        rwl.writeLock().unlock();
                    }
                }

                // kill the job if it is writing out more than the max stdout limit
                // if it has been terminated already, move on and wait for it to clean up after itself
                if ((stdOutFile != null) && (stdOutFile.exists())
                        && (maxStdoutSize != null) && (stdOutFile.length() > maxStdoutSize)
                        && (!terminated)) {
                    LOG.warn("Killing job " + job.getJobID() + " as its stdout is greater than limit");
                    // kill the job - no need to update status, as it will be updated during next iteration
                    try {
                        JobManagerFactory.getJobManager(job.getJobType()).kill(job);
                        terminated = true;
                    } catch (CloudServiceException e) {
                        LOG.error("Can't kill job " + job.getJobID()
                                + " after exceeding stdout limit", e);
                        // continue - hoping that it can get cleaned up during next iteration
                    }
                }
            }
        }

        return proc.exitValue();
    }

    /**
     * The main run method for this thread - wait till it finishes, and manage
     * job state in DB.
     */
    @Override
    public void run() {
        GenieNodeStatistics stats = GenieNodeStatistics.getInstance();

        // flag to track if the job is killed. Used to determine status of email sent.
        boolean killed = false;

        // wait for process to complete
        int exitCode = waitForExit();
        job.setExitCode(exitCode);

        ReentrantReadWriteLock rwl = PersistenceManager.getDbLock();
        try {
            // get job status from the DB to ensure it was not killed
            // acquire a write lock first
            rwl.writeLock().lock();

            Job dbJI = pm.getEntity(job.getJobID(),
                    Job.class);

            // only update status if not KILLED
            if ((dbJI.getStatus() != null)
                    && dbJI.getStatus() != JobStatus.KILLED) {

                if (exitCode != SubprocessStatus.SUCCESS.code()) {
                    // all other failures except s3 log archival failure
                    LOG.error("Failed to execute job, exit code: "
                            + exitCode);
                    String errMsg = Types.SubprocessStatus.message(exitCode);
                    if ((errMsg == null) || (errMsg.isEmpty())) {
                        errMsg = "Please look at job's stderr for more details";
                    }
                    job.setJobStatus(JobStatus.FAILED,
                            "Failed to execute job, Error Message: " + errMsg);
                    // incr counter for failed jobs
                    stats.incrGenieFailedJobs();
                } else {
                    // success
                    job.setJobStatus(JobStatus.SUCCEEDED,
                            "Job finished successfully");
                    // incr counter for successful jobs
                    stats.incrGenieSuccessfulJobs();
                }

                // set the archive location - if needed
                if (!job.isDisableLogArchival()) {
                    job.setArchiveLocation(NetUtil.getArchiveURI(job.getJobID()));
                }

                // update the job status
                pm.updateEntity(job);
                rwl.writeLock().unlock();
            } else {
                // if job status is killed, the kill thread will update status
                LOG.debug("Job has been killed - will not update DB: "
                        + job.getJobID());
                killed = true;
                rwl.writeLock().unlock();
            }
        } finally {
            // ensure that we always unlock
            if (rwl.writeLock().isHeldByCurrentThread()) {
                rwl.writeLock().unlock();
            }
        }

        // Check if user email address is specified. If so
        // send an email to user about job completion.
        String emailTo = job.getUserEmail();

        if (emailTo != null) {
            LOG.info("User email address: " + emailTo);

            if (sendEmail(emailTo, killed)) {
                // Email sent successfully. Update success email counter
                stats.incrSuccessfulEmailCount();
            } else {
                // Failed to send email. Update email failed counter
                LOG.warn("Failed to send email.");
                stats.incrFailedEmailCount();
            }
        }
    }

    /**
     * Check the properties file to figure out if an email needs to be sent at
     * the end of the job. If yes, get mail properties and try and send email
     * about Job Status.
     *
     * @return 0 for success, -1 for failure
     */
    private boolean sendEmail(String emailTo, boolean killed) {
        LOG.debug("called");

        if (!config.getBoolean("netflix.genie.server.mail.enable", false)) {
            LOG.warn("Email is disabled but user has specified an email address.");
            return false;
        }

        // Sender's email ID
        String fromEmail = config.getString("netflix.genie.server.mail.smpt.from", "no-reply-genie@geniehost.com");
        LOG.info("From email address to use to send email: "
                + fromEmail);

        // Set the smtp server hostname. Use localhost as default
        String smtpHost = config.getString("netflix.genie.server.mail.smtp.host", "localhost");
        LOG.debug("Email smtp server: "
                + smtpHost);

        // Get system properties
        Properties properties = new Properties();

        // Setup mail server
        properties.setProperty("mail.smtp.host", smtpHost);

        // check whether authentication should be turned on
        Authenticator auth = null;

        if (config.getBoolean("netflix.genie.server.mail.smtp.auth", false)) {
            LOG.debug("Email Authentication Enabled");

            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.auth", "true");

            String userName = config.getString("netflix.genie.server.mail.smtp.user");
            String password = config.getString("netflix.genie.server.mail.smtp.password");

            if ((userName == null) || (password == null)) {
                LOG.error("Authentication is enabled and username/password for smtp server is null");
                return false;
            }
            LOG.debug("Constructing authenticator object with username"
                    + userName
                    + " and password "
                    + password);
            auth = new SMTPAuthenticator(userName,
                    password);
        } else {
            LOG.debug("Email authentication not enabled.");
        }

        // Get the default Session object.
        Session session = Session.getInstance(properties, auth);

        try {
            // Create a default MimeMessage object.
            MimeMessage message = new MimeMessage(session);

            // Set From: header field of the header.
            message.setFrom(new InternetAddress(fromEmail));

            // Set To: header field of the header.
            message.addRecipient(Message.RecipientType.TO,
                    new InternetAddress(emailTo));

            JobStatus jobStatus;

            if (killed) {
                jobStatus = JobStatus.KILLED;
            } else {
                jobStatus = job.getStatus();
            }

            // Set Subject: header field
            message.setSubject("Genie Job "
                    + job.getJobName()
                    + " completed with Status: "
                    + jobStatus);

            // Now set the actual message
            String body = "Your Genie Job is complete\n\n"
                    + "Job ID: "
                    + job.getJobID()
                    + "\n"
                    + "Job Name: "
                    + job.getJobName()
                    + "\n"
                    + "Status: "
                    + job.getStatus()
                    + "\n"
                    + "Status Message: "
                    + job.getStatusMsg()
                    + "\n"
                    + "Output Base URL: "
                    + job.getOutputURI()
                    + "\n";

            message.setText(body);

            // Send message
            Transport.send(message);
            LOG.info("Sent email message successfully....");
            return true;
        } catch (MessagingException mex) {
            LOG.error("Got exception while sending email", mex);
            return false;
        }
    }

    private static class SMTPAuthenticator extends Authenticator {

        private final String username;
        private final String password;

        /**
         * Default constructor.
         */
        SMTPAuthenticator(final String username, final String password) {
            this.username = username;
            this.password = password;
        }

        /**
         * Return a PasswordAuthentication object based on username/password.
         */
        @Override
        public PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(username, password);
        }
    }
}
