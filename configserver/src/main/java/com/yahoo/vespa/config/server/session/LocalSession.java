// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.config.application.api.ApplicationFile;
import com.yahoo.config.application.api.ApplicationMetaData;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.io.IOUtils;
import com.yahoo.path.Path;
import com.yahoo.transaction.AbstractTransaction;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.transaction.Transaction;
import com.yahoo.vespa.config.server.TimeoutBudget;
import com.yahoo.vespa.config.server.application.TenantApplications;
import com.yahoo.vespa.config.server.host.HostValidator;

import java.io.File;

/**
 * A LocalSession is a session that has been created locally on this configserver. A local session can be edited and
 * prepared. Deleting a local session will ensure that the local filesystem state and global zookeeper state is
 * cleaned for this session.
 *
 * @author Ulf Lilleengen
 */
// This is really the store of an application, whether it is active or in an edit session
// TODO: Separate the "application store" and "session" aspects - the latter belongs in the HTTP layer   -bratseth
public class LocalSession extends Session {

    protected final ApplicationPackage applicationPackage;
    private final TenantApplications applicationRepo;
    private final File serverDBSessionDir;
    private final HostValidator<ApplicationId> hostValidator;

    /**
     * Creates a session. This involves loading the application, validating it and distributing it.
     *
     * @param sessionId The session id for this session.
     */
    public LocalSession(TenantName tenant, long sessionId, ApplicationPackage applicationPackage,
                        SessionZooKeeperClient sessionZooKeeperClient, File serverDBSessionDir,
                        TenantApplications applicationRepo, HostValidator<ApplicationId> hostValidator) {
        super(tenant, sessionId, sessionZooKeeperClient);
        this.serverDBSessionDir = serverDBSessionDir;
        this.applicationPackage = applicationPackage;
        this.applicationRepo = applicationRepo;
        this.hostValidator = hostValidator;
    }

    public ApplicationFile getApplicationFile(Path relativePath, Mode mode) {
        if (mode.equals(Mode.WRITE)) {
            markSessionEdited();
        }
        return applicationPackage.getFile(relativePath);
    }

    void setPrepared() {
        setStatus(Session.Status.PREPARE);
    }

    private Transaction createSetStatusTransaction(Status status) {
        return sessionZooKeeperClient.createWriteStatusTransaction(status);
    }

    private void setStatus(Session.Status newStatus) {
        sessionZooKeeperClient.writeStatus(newStatus);
    }

    public Transaction createActivateTransaction() {
        sessionZooKeeperClient.createActiveWaiter();
        Transaction transaction = createSetStatusTransaction(Status.ACTIVATE);
        transaction.add(applicationRepo.createPutTransaction(sessionZooKeeperClient.readApplicationId(), getSessionId()).operations());
        return transaction;
    }

    private void markSessionEdited() {
        setStatus(Session.Status.NEW);
    }

    public long getActiveSessionAtCreate() {
        return applicationPackage.getMetaData().getPreviousActiveGeneration();
    }

    /** Add transactions to delete this session to the given nested transaction */
    public void delete(NestedTransaction transaction) {
        transaction.add(sessionZooKeeperClient.deleteTransaction(), FileTransaction.class);
        transaction.add(FileTransaction.from(FileOperations.delete(serverDBSessionDir.getAbsolutePath())));
    }

    public void waitUntilActivated(TimeoutBudget timeoutBudget) {
        sessionZooKeeperClient.getActiveWaiter().awaitCompletion(timeoutBudget.timeLeft());
    }

    public enum Mode {
        READ, WRITE
    }

    public ApplicationMetaData getMetaData() { return applicationPackage.getMetaData(); }

    public ApplicationPackage getApplicationPackage() { return applicationPackage; }

    public HostValidator<ApplicationId> getHostValidator() { return hostValidator; }

    // The rest of this class should be moved elsewhere ...
    
    private static class FileTransaction extends AbstractTransaction {
        
        public static FileTransaction from(FileOperation operation) {
            FileTransaction transaction = new FileTransaction();
            transaction.add(operation);
            return transaction;
        }

        @Override
        public void prepare() { }

        @Override
        public void commit() {
            for (Operation operation : operations())
                ((FileOperation)operation).commit();
        }

    }
    
    /** Factory for file operations */
    private static class FileOperations {
        
        /** Creates an operation which recursively deletes the given path */
        public static DeleteOperation delete(String pathToDelete) {
            return new DeleteOperation(pathToDelete);
        }
        
    }
    
    private interface FileOperation extends Transaction.Operation {

        void commit();
        
    }

    /** 
     * Recursively deletes this path and everything below. 
     * Succeeds with no action if the path does not exist.
     */
    private static class DeleteOperation implements FileOperation {

        private final String pathToDelete;
        
        DeleteOperation(String pathToDelete) {
            this.pathToDelete = pathToDelete;
        }
        
        @Override
        public void commit() {
            // TODO: Check delete access in prepare()
            IOUtils.recursiveDeleteDir(new File(pathToDelete));
        }

    }

}
