/* @author Thomas Ansill
 * CSCI-651-03
 * Project 2
 */
import java.util.concurrent.CountDownLatch;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/** Convenience abstract class that shares similar features in Server and Client classes */
public abstract class Application{
    /** Default Timeout */
    protected int defaulttimeout;
    /** Timeout value */
    protected volatile int timeout;
    /** Quiet flag */
    protected boolean quiet;
    /** Receiver object */
    protected Receiver receiver;
    /** CountDownLatch object */
    protected CountDownLatch cdl;
    /** Timeout Timer */
    protected Timeout timeoutthread;
    /** Port number */
    protected int port;
    /** Mailbox to send or receive packets */
    protected DatagramSocket mailbox;
    /** State of the Application */
    protected FSM state = FSM.CLOSED;

    // TCP control
    protected int seq_num = 0;
    protected int ack_num = 0;
    protected int last_time = 0;

    //File control
    protected int file_seq_offset = -1;

    // Round Trip Time
    protected double estimatedRTT = (0.125 * this.defaulttimeout);
    protected double devRTT = 0.25 * Math.abs(this.defaulttimeout - this.estimatedRTT);

    // Fast retransmit
    protected int last_ack = 0;

    /** Convenience method for verbose mode - if quiet is set to false, this method will print amessage with newline appended
     *  @param Message to be printed appended with a newline
     */
    protected void println(String message){
        print(message + "\n");
    }

    /** Convenience method for verbose mode - if quiet is set to false, this method will print a message
     *  @param Message to be printed
     */
    protected void print(String message){
        if(!quiet) System.out.print(message);
    }

    /** Convenience method to get System's time in Milliseconds
     *  @return System's time in Milliseconds
     */
    protected int getTime(){
        // System.currentTimeMillis() was giving me troubles, went with System.nanoTime()
        return (int)(System.nanoTime()/1000000);
    }

    /** Method that recalculates Round Trip Time with a sample round trip time to find optimal timeout duration
     *  @param sampleRTT sample round trip time time in Milliseconds
     */
    protected void recalculateRTT(int sampleRTT){
        // Calculate RTT
        this.estimatedRTT = (0.875 * this.estimatedRTT + (0.125 * sampleRTT));
        this.devRTT = 0.75 * this.devRTT + 0.25 * Math.abs(sampleRTT - this.estimatedRTT);
        int RTT = (int)Math.ceil(estimatedRTT + 4 * devRTT);

        // Don't want to have duration be higher than defaulttimeout
        this.timeout = Math.min(RTT, this.defaulttimeout);

        // Verbose message
        println("#\n################ RTT ################\n## estimatedRTT: "
            + this.estimatedRTT + "\n## devRTT:       "
            + this.devRTT + "\n## sampleRTT: "
            + sampleRTT + "ms\tresult: "
            + RTT + "ms\n#####################################");
    }
}
