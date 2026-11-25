package eu.intracherche.intracherche.sherlock;

public class SherlockQueueFullException extends RuntimeException {
    public SherlockQueueFullException() {
        super("Sherlock queue is full");
    }
}
