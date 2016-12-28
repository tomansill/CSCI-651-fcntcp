/* @author Thomas Ansill
 * CSCI-651-03
 * Project 2
 */
import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;

/** The Client aspect for File Transfer Application */
public class Client extends Application{
    private InetAddress address;
    private File file;
    private RandomAccessFile ra;

    // Congestation control
    private int cwnd = 1;

    // Used if timeout forgets if sender needs to ACK server's SYN bit
    private int server_pre_syn = 0;
    private int fin_count = 0;

    // GBN Protocol
    private ArrayList<Packet> packets = new ArrayList<Packet>();
    private ArrayList<Integer> timeOfSend = new ArrayList<Integer>();
    private int lengthWrite = 0;

    // Congestation control
    private short server_available_buffer = (short)1000;
    private int bytes_in_flight = 0;

    // Fast retransmit
    private int number_of_acks = 0;

    /** Constructor for Client
     *  @param address  Destination address
     *  @param port     Destination port
     *  @param file     File to send to Destination
     *  @param timeout  Timeout duration
     *  @param  quiet   True to quiet the verbose messages otherwise false to quiet the verbose messages
     */
    public Client(InetAddress address, int port, File file, int timeout, boolean quiet) throws IOException{
        this.address = address;
        this.port = port;
        this.file = file;
        this.defaulttimeout = timeout;
        this.quiet = quiet;
        this.ra = new RandomAccessFile(file, "r");
        this.mailbox = new DatagramSocket();
        this.cdl = new CountDownLatch(1);
        this.receiver = new Receiver(mailbox, cdl, quiet);
    }

    /** Main loop for the Client */
    public void start() throws SocketException, Exception{
        // Publish the file's hash - if debugging is not enabled. If debugging is enabled, this line may get lost in many prints
        if(quiet) System.out.println("MD5 hash of the file: " + Utility.getFileHash(this.file));

        // Start the receiver
        receiver.start();

        println("\n################### Synchronization #########################\n");
        this.timeout = this.defaulttimeout;

        // Send the SYN
        timeoutthread = new Timeout(this.cdl, startSYN());
        timeoutthread.start();
        this.state = FSM.SYN;

        //if(quiet) System.out.print("Transfer in progress...");

        // Process packets queue
        while(true){
            // Wait for anything from receiver or timeout
            if(!receiver.hasPacket()) cdl.await();

            //println("$\n$ seq_num: " + seq_num + "\t ack_num: " + ack_num + "\n$");

            // Reset the CDL
            cdl = new CountDownLatch(1);
            receiver.setCountDownLatch(cdl);

            // Clean up the timeout thread
            if(timeoutthread != null){
                timeoutthread.interruptTime();
                timeoutthread = null;
            }

            // Check event
            int duration;
            if(receiver.hasPacket()) duration = processPacket(receiver.getPacket()); // Received a packet
            else duration = processTimeout(); // Timed out

            // Verbose message
            if(duration != -1) println("Timeout set to: " + duration + "ms");
            else println("");

            // Set timeout
            if(duration == 0){
                duration = processTimeout();

                // Verbose message
                println("Timeout set to: " + duration + "ms");
            }else if(duration == -1L) break;

            // Start the timeout
            timeoutthread = new Timeout(this.cdl, duration);
            timeoutthread.start();
        }
        // Clean up
        this.cleanUp();

        println("\nStopping the client");

        println("\nInput MD5 Hash: " + Utility.getFileHash(this.file));
        //if(quiet) System.out.print("\r                                  ");
        //if(quiet) System.out.print("\r");
    }

    /** Sends SYN message
     *  @return Time out duration
     */
    public int startSYN() throws Exception{
        // Verbose message
        println("# Sending SYN packet");

        // Send SYN message
        sendPacket(false, true, false, null);

        // Verbose message
        println("# Time Out Duration: " + this.timeout);

        // For RTT
        this.last_time = getTime();

        // Begin the timeout
        return this.timeout;
    }

    /** Packet sending method for Go-N-Back protocol
     *  @param ack To mark the message with ack bit
     *  @return timeout duration
     */
    public int processGBNFileTransfer(boolean ack) throws Exception{
        // Find the remaining bytes in the file
        long remaining = ra.length() - ra.getFilePointer();

        //if(quiet) System.out.print("\rProgress: " + (int)(((ra.getFilePointer()*1.0)/ra.length())*100) + "%");

        println("# File Length remaining: " + remaining
            + "\t unacked sent packets: " + packets.size()
            + "  cwnd: " + cwnd
            + "  packets to be sent: " + (cwnd - packets.size())
            + "\n# bytes in flight: " + this.bytes_in_flight
            + "\twindow: " + this.server_available_buffer
            + "\tbytes_can_send: " + (this.server_available_buffer - lengthWrite - this.bytes_in_flight));

        if(remaining == 0){
            print("#\n## GBN Finished reading! - ");
            if(timeOfSend.size() == 0){
                print("All packets are ACKED, can exit now\n");
                return -1;
            }else{
                print("Not all packets are ACKED yet, wait for more ACKs - ");
                int time = (int)(System.nanoTime()/1000000);
                int comp = timeOfSend.get(0);
                int result = (time - comp);
                int timeoutdur = this.timeout - result;
                return timeoutdur < 0 ? 0 : timeoutdur;
            }
        }

        if(file_seq_offset == -1){
            //if(quiet) System.out.print("\r                                                       ");
            println("## Starting file transfer ##\n#");
            file_seq_offset = seq_num;
        }

        // Make and send packet for remaining CWND window
        int packets_to_be_sent = cwnd - packets.size();
        for(int i = 0; i < packets_to_be_sent; i++){
            // Congestation control
            if(0 >= (this.server_available_buffer - lengthWrite - this.bytes_in_flight)){
                // Verbose message
                println("# Server is too congested, stop sending packets and wait");

                // Reduce the window
                cwnd /= 2;
                cwnd += cwnd == 0 ? 1 : 0;

                // Double the timeout
                this.timeout += this.timeout + ((this.timeout == 0) ? 2 : 0);
                this.timeout = Math.min(this.timeout, this.defaulttimeout);
                break;
            }

            // Build a buffer to hold data from file
            int len = Math.min(Packet.MAX_DATA_LENGTH, (int)remaining);
            len = Math.min(len, server_available_buffer - lengthWrite);
            byte[] buffer = new byte[len];

            // Update the bytes read status
            int new_seq = this.seq_num + lengthWrite;
            lengthWrite += ra.read(buffer);
            remaining = ra.length() - ra.getFilePointer();
            this.bytes_in_flight += lengthWrite;

            // Send the packet
            Packet packet = sendPacket(new_seq, this.ack_num, ack, false, false, buffer);

            // Verbose message
            print("# packet " + (i+1) + " out of " + packets_to_be_sent);
            println("  ## SENDING PACKET ## seq: " + new_seq + "\tlength: " + packet.length + " fp: " + ra.getFilePointer()
                + (remaining == 0 ? " Finished!" : ""));

            // Add to the unacked buffer
            packets.add(packet);
            int time = (int)(System.nanoTime()/1000000);
            timeOfSend.add(time);

            // If last packet, stop the loop
            if(remaining == 0){
                this.state = FSM.ESTABLISHED_FIN;
                break;
            }
        }

        // Verbose message
        print("#\n## Packets ending complete - ");

        // No packets may be sent due to congested server. Just wait then try again.
        // Next ACK packets or timeout will start the sending again
        if(timeOfSend.size() == 0) return this.timeout;

        // Return the timeout relative to the first unacked packet
        int time = (int)(System.nanoTime()/1000000);
        int comp = timeOfSend.get(0);
        int result = (time - comp);
        int timeoutdur = this.timeout - result;
        timeoutdur = timeoutdur < 0 ? 0 : timeoutdur;
        return timeoutdur;
    }

    /** Method to handle acknowledgements in file transfer in Go-N-Back protocol style
     *  @param ack Recent packet acknowledgement number
     *  @return timeout duration
     */
    public int acknowledgeGBNFileTransfer(int ack) throws Exception{
        // Check if there's any packet to ack for
        if(packets.size() > 0){
            // Verbose message
            println("# Unacked Packets: " + packets.size() + " top packet seq: " + packets.get(0).seq_num);

            // Check if this packet is anticipated
            int anticipated_seq = packets.get(0).length + this.seq_num;

            print("#\n# Anticipated Ack Num: " + anticipated_seq + "\t");

            // Check if is anticipated
            if(anticipated_seq == ack){
                // Verbose message
                println("In-order packet\n#");

                //Increase window
                cwnd++;

                // Recalculate RTT
                recalculateRTT(getTime() - timeOfSend.get(0));

                // Adjust the length of unacked bytes
                lengthWrite -= packets.get(0).length;
                this.bytes_in_flight -= packets.get(0).length;

                // Advance the sequence number
                this.seq_num = ack;

                // Dequeue the window
                packets.remove(0);
                timeOfSend.remove(0);

                // If this is the last acked packet then send FIN
                if(this.state == FSM.ESTABLISHED_FIN && packets.size() == 0){
                    sendPacket(this.seq_num, this.ack_num, false, false, true, null);
                    this.state = FSM.FIN;
                    print("## Finished with everything! FIN Sent! Seq: " + this.seq_num + " ack: " + this.ack_num + " ");
                    return this.timeout;
                }

                // Send packets
                return processGBNFileTransfer(false);
            }else{
                // Verbose message
                print("Out of order packet - drop this packet because ");
                if(ack <= this.seq_num) println("packet is already acked");
                else println("packet is beyond the order");
                if(ack == this.seq_num){
                    // Check if counts as fast retransmit
                    if(ack == last_ack) number_of_acks++;
                    else{
                        last_ack = ack;
                        number_of_acks = 1;
                    }
                    // Verbose message
                    println("# Attempt Fast Retransmit: " + number_of_acks + " seq: " + this.seq_num);

                    // Fire fast retransmit if more than 1 acks
                    if(number_of_acks >= 2){
                        // Server was calling for a fast retransmit
                        println("# Fast retransmit!");
                        number_of_acks = 0;
                        return timeoutGBNFileTransfer();
                    }
                }

                print("$\n## ");

                // if that ack was intended for next ack, let next timeout take care of that
                if(timeOfSend.size() == 0) return this.timeout;
                int time = (int)(System.nanoTime()/1000000);
                int comp = timeOfSend.get(0);
                int timeoutdur = this.timeout - (time - comp);
                timeoutdur = timeoutdur < 0 ? 0 : timeoutdur;
                return timeoutdur;
            }
        }
        print("## Congested traffic, wait until it clears - ");
        // Try reduce bytes in flight - I'm making this up
        this.bytes_in_flight *= 3;
        this.bytes_in_flight /= 4;
        this.bytes_in_flight = this.bytes_in_flight < 0 ? 1 : this.bytes_in_flight;
        // Double the timeout
        this.timeout += this.timeout + ((this.timeout == 0) ? 2 : 0);
        this.timeout = Math.min(this.timeout, this.defaulttimeout);
        return this.timeout;
    }

    /** Timeout method for Go-N-Back protocol
     *  @return timeout duration
     */
    public int timeoutGBNFileTransfer() throws Exception{
        // Reset the file pointer
        ra.seek(ra.getFilePointer() - lengthWrite);
        lengthWrite = 0;

        // Clear the window
        boolean ack = false;
        if(packets.size() != 0) ack = packets.get(0).ack_bit;
        packets.clear();
        timeOfSend.clear();

        // Try reduce bytes in flight - I'm making this up
        this.bytes_in_flight *= 3;
        this.bytes_in_flight /= 4;
        this.bytes_in_flight = this.bytes_in_flight < 0 ? 1 : this.bytes_in_flight;

        // Try bump up and see - this too
        if(this.server_available_buffer == 0) this.server_available_buffer += 20;

        // Probably has some packets in receiver buffer, won't be needing them
        receiver.clearBuffer();

        // If ESTABLISHED_FIN was sent, undo it
        if(this.state == FSM.ESTABLISHED_FIN) this.state = FSM.ESTABLISHED;

        // Change the cwnd
        cwnd /= 2;
        cwnd += cwnd == 0 ? 1 : 0;

        // Send packets again
        return processGBNFileTransfer(ack);
    }

    /** Convenience method to fire away TCP packet
     *  @param ack_bit Acknowledgement Bit in packet. True if enabled, otherwise false
     *  @param syn_bit Synchronization Bit in packet. True if enabled, otherwise false
     *  @param fin_bit Finish Bit in packet. True if enabled, otherwise false
     *  @param data Data in byte array - null if no data is to be sent
     *  @return Packet
     */
    public Packet sendPacket(boolean ack_bit, boolean syn_bit, boolean fin_bit, byte[] data) throws Exception{
        return sendPacket(this.seq_num, this.ack_num, ack_bit, syn_bit, fin_bit, data);
    }

     /** Convenience method to fire away TCP packet
      *  @param seq_num Sequence Number
      *  @param ack_num Acknowledgement Number
      *  @param ack_bit Acknowledgement Bit in packet. True if enabled, otherwise false
      *  @param syn_bit Synchronization Bit in packet. True if enabled, otherwise false
      *  @param fin_bit Finish Bit in packet. True if enabled, otherwise false
      *  @param data Data in byte array - null if no data is to be sent
      *  @return Packet
      */
    public Packet sendPacket(int seq_num, int ack_num, boolean ack_bit, boolean syn_bit, boolean fin_bit, byte[] data) throws Exception{
        // Build the packet
        Packet packet = new Packet(seq_num, ack_num, ack_bit, fin_bit, syn_bit, receiver.getAvailableBuffer(), data, mailbox.getLocalAddress(), mailbox.getLocalPort());

        // Convert the packet to byte array
        byte[] tcp_packet = packet.toByteArray();


        /*
        // Packet dropper test
        int chance = (int)(Math.random() * 4);
        if(chance != 1) mailbox.send(new DatagramPacket(tcp_packet, tcp_packet.length, address, port));
        //else System.out.println("&&&&& FAILURE &&&&&");
        */

        // Send the packet
        mailbox.send(new DatagramPacket(tcp_packet, tcp_packet.length, address, port));

        // Verbose message
        //print("## SENDING PACKET ##\n" + packet);
        return packet;
    }

    /** Processes the packet assuming that it passed the checksum test
     *  @param packet Packet to be processed
     *  @return Time out duration
     */
    public int processPacket(Packet packet) throws Exception{
        //TCP control bits
        boolean ack_bit = false;
        boolean syn_bit = false;
        boolean fin_bit = false;

        // Congestation control
        this.server_available_buffer = packet.window;

        // Verbose message
        print("\n### RECEIVING PACKET ## Seq: " + packet.seq_num + " Ack_num: " + packet.ack_num + " Window: " + this.server_available_buffer + " ");
        if(packet.ack_bit) print("ACK ");
        if(packet.syn_bit) print("SYN ");
        if(packet.fin_bit) print("FIN ");
        println("\n#");


        // Check if SYN message
        if(packet.syn_bit && this.state == FSM.SYN){
            // Verbose message
            println("# ACK to Server's SYN");

            // Add time to RTT
            recalculateRTT(getTime() - this.last_time);

            // Received the SYN bit, increment the ack bit
            this.ack_num = packet.seq_num + 1;

            // Required to ACK the SYN bit
            ack_bit = true;
        }

        if(packet.ack_bit && packet.fin_bit && !packet.syn_bit && this.state == FSM.FIN){
            //int anticipated_seq =

            // Verbose message
            println("#\n################### Finished! #########################\n#");
            print("# FIN is acked ");

            // Send ACK to their FIN and exit
            sendPacket(this.seq_num, this.ack_num+1, true, false, false, null);
            return -1;
        }

        if(packet.ack_bit && !packet.fin_bit && !packet.syn_bit && (this.state == FSM.ESTABLISHED || this.state == FSM.ESTABLISHED_FIN)){
            return acknowledgeGBNFileTransfer(packet.ack_num);
        }

        // Check if ACK message
        if(packet.ack_bit && this.state == FSM.SYN){
            // Verbose message
            println("#\n################### Established! #########################\n#");

            // Advance the sequence number
            this.seq_num = packet.ack_num;

            // Change state to established
            this.state = FSM.ESTABLISHED;

            // Convenience data for timeouts resubmit
            this.server_pre_syn = packet.seq_num;

            // Start the transfer
            return processGBNFileTransfer(true);
        }

        // Store last time for RTT
        this.last_time = getTime();

        // Send packet when appropriate
        if(ack_bit || syn_bit || fin_bit) sendPacket(ack_bit, syn_bit, fin_bit, null);

        // Exit if FIN is already acked
        if(this.state == FSM.FIN_ACK) return -1;

        //if(this.seq_num > 5) return -1;

        // Default time out duration
        println("# processPacket method ends");
        return this.timeout;
    }

    /** Process the time out call
     *  @return time out duration
     */
    public int processTimeout() throws Exception{
        // Verbose message
        println("\n## Timed out ##");

        // Double the timeout
        this.timeout += this.timeout + ((this.timeout == 0) ? 2 : 0);
        this.timeout = Math.min(this.timeout, this.defaulttimeout);

        // Check if timeout was from server not responding to SYN message
        if(this.state == FSM.SYN){
            // Verbose message
            println("# SYN packet sent by client timed out. Resending SYN");

            //Send the SYN again
            return startSYN();
        }

        if(this.state == FSM.ESTABLISHED || this.state == FSM.ESTABLISHED_FIN){
            return timeoutGBNFileTransfer();
        }

        if(this.state == FSM.FIN){
            // Verbose message
            print("## FIN packet sent by client timed out. Resending FIN ");

            // Store last time for RTT
            this.last_time = getTime();

            // Send the packet and set timeout
            sendPacket(this.seq_num, this.ack_num, false, false, true, null);

            // Covers a scenario where server may get ACK but fails to send ACK
            this.fin_count++;
            if(this.fin_count % 5 == 0) return -1;

            return this.timeout;
        }

        println("# processTimeout method ends");
        return -1;
    }

    /** Clean up method - cleans up everything */
    public void cleanUp() throws Exception{
        ra.close();
        receiver.close();
        receiver.interrupt();
        receiver = null;
        timeoutthread = null;
        cdl = null;
        mailbox.close();
    }
}
