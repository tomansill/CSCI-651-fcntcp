/*  @author Thomas Ansill
 *  @date November 4, 2016
 */
import java.util.concurrent.CountDownLatch;
/** A simple Timeout object that informs the parent thread with CountDownLatch when time is up */
public class Timeout extends Thread{
    /** The countdownlatch object - used to signal the parent thread that time is up */
    private CountDownLatch cdl;
    /** The timeout duration in milliseconds */
    private int duration;
    /** Interrupt flag */
    private volatile boolean interrupt = false;

    /** Constructor for Timeout object
     *  @param cdl  CountDownLatch object that is used to inform the parent thread that the time is up
     *  @param duration The duration of time out
     */
    public Timeout(CountDownLatch cdl, int duration){
        this.cdl = cdl;
        if(duration >= 0) this.duration = duration;
        else this.duration = 0;
    }

    /** Thread's run method */
    public void run(){
        try{
            //System.out.println("Started timeout " + this.duration);
            //Sleep for a duration of time
            long time = System.currentTimeMillis();
            while(!interrupt && (System.currentTimeMillis() - time) < this.duration);

            //Count down to inform the parent thread that time out is over
            if(!interrupt)cdl.countDown();
        }catch(Exception e){
            // Not supposed to happen
            e.printStackTrace();
        }
    }
    /** Method to interrupt the timer */
    public synchronized void interruptTime(){
        this.interrupt = true;
    }
}
