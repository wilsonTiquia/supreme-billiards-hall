package com.supremebilliardshall.billiards_hall_system.exception;

// Thrown when a global admin has no active branch selected and there is more than one to
// choose from. Deliberately loud: silently picking a branch would mean an admin reading one
// hall's figures while believing they are another's, with nothing in the response to say so.
public class BranchNotSelectedException extends RuntimeException{
    public BranchNotSelectedException(int activeBranchCount) {
        super("No branch selected. " + activeBranchCount
                + " active branches exist; select one before continuing.");
    }
}
