/* @author Thomas Ansill
 * CSCI-651-03
 * Project 2
 */
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.InetSocketAddress;

/** Receiver class - Object that runs in its own thread and collects all uncorrupted packets */
public class Receiver extends Thread{
    /** Constant for Receiver buffer - I found that 2048 is a good number to handle huge file transfer like 104MB file */
    private final static short MAX_AVAILABLE_BUFFER_SIZE = (short)2048;
    /** Mailbox to receive messages - can be used to send messages too */
    private DatagramSocket mailbox;
    /** Buffer of packets */
    private ArrayBlockingQueue<Packet> packets;
    /** Control bit to shut down the Receiver */
    private boolean stop = false;
    /** CountDownLatch object to inform the parent thread when a new packet was just added */
    private CountDownLatch cdl;
    /** Quiet flag */
    private boolean quiet;
    /** size of available buffer to receive */
    private int availableBuffer = MAX_AVAILABLE_BUFFER_SIZE;

    /** Constructor for Receiver
     *  @param mailbox  Mailbox to receive packets
     *  @param cdl  CountDownLatch object to inform the parent thread when a packet arrives in the Receiver's buffer
     *  @param quiet Quiet flag
     */
    public Receiver(DatagramSocket mailbox, CountDownLatch cdl, boolean quiet){
        this.mailbox = mailbox;
        this.packets = new ArrayBlockingQueue<Packet>((MAX_AVAILABLE_BUFFER_SIZE/Packet.MAX_DATA_LENGTH) + 1);
        this.cdl = cdl;
        this.quiet = quiet;
    }

    /** Receiver run method - continuously collecting all packets - will automatically drop corrupted packets*/
    public void run(){
        // Run forever until stop bit is set
        while(!this.stop){
            try{
                // Only accept 567 byte - can be changed to any value but recommended MSS is 567
                byte[] payload = new byte[567];
                DatagramPacket packet = new DatagramPacket(payload, payload.length);
                mailbox.receive(packet);

                /*
                // Corrupter test
                int chance = (int)(Math.random() * 6);
                if(chance == 1){
                    int loop = (int)(Math.random() * 100);
                    for(int i = 0; i < loop; i++){
                        int loc = (int)(Math.random() * payload.length-1);
                        packet.getData()[loc] = (byte)(Math.random() * 128);
                    }
                }
                */

                // Build Packet - packet may become null when they fail checksum test
                Packet pkt = Packet.buildPacketFromByteArray(packet.getData(), packet.getLength(), packet.getAddress(), packet.getPort());

                // Check if packet is null and if Receiver has a room for the packet to be fit in
                if(pkt != null && this.availableBuffer > 0){
                    if(packets.offer(pkt)){
                        this.availableBuffer -= pkt.length;
                        cdl.countDown();
                    }
                }
            }catch(SocketException se){
                //do nothing
            }catch(Exception e){
                if(!quiet) System.out.println("!! Packet is corrupted! !! - Dropped automatically");
            }
        }
    }

    /** Gets length of available buffer length
     *  @return length of available buffer
     */
    public synchronized short getAvailableBuffer() throws InterruptedException{
        return (short)(availableBuffer < 0 ? 0 : availableBuffer);
    }

    /** Gets the number of packets in the buffer
     *  @return number of packets in the buffer
     */
    public synchronized int getSize() throws InterruptedException{
        return packets.size();
    }

    /** Gets the packet from the buffer, will remove the packet from the buffer
     *  @return Packet on the front of the buffer
     */
    public synchronized Packet getPacket() throws InterruptedException{
        Packet pkt = packets.poll();
        if(pkt != null) this.availableBuffer += pkt.length;
        return pkt;
    }

    /** Check if buffer has anything in it
     *  @return true if something is in the buffer, otherwise false
     */
    public synchronized boolean hasPacket() throws InterruptedException{
        return !packets.isEmpty();
    }

    /** Discards all packets in the buffer */
    public synchronized void clearBuffer() throws InterruptedException{
        this.availableBuffer = MAX_AVAILABLE_BUFFER_SIZE;
        packets.clear();
    }

    /** Closes the Receiver */
    public void close(){
        this.stop = true;
    }

    /** Assign a CountDownLatch to this Receiver
     *  @param cdl CountDownLatch object
     */
    public void setCountDownLatch(CountDownLatch cdl){
        this.cdl = cdl;
    }

    /** Gets the CountDownLatch from this Receiver
     *  @return CountDownLatch object
     */
    public CountDownLatch getCountDownLatch(){
        return this.cdl;
    }
}
