import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
public class Server extends Application{
    /** Client's ip address */
    private InetAddress clientAddress;
    /** Client's port */
    private int clientPort;
    /** "Output" buffer */
    StringBuffer sb = new StringBuffer();
    /** Fast retransmit */
    private int retransmitcount = 0;

    /** Constructor for Server
     *  @param port Server port
     *  @param quiet True to quiet the verbose messages otherwise false to quiet the verbose messages
     */
    public Server(int port, boolean quiet) throws SocketException{
        this.mailbox = new DatagramSocket(port);
        this.port = port;
        this.quiet = quiet;
        //this.ra = new RandomAccessFile(file, "w");
        this.cdl = new CountDownLatch(1);
        this.receiver = new Receiver(mailbox, cdl, quiet);
        this.defaulttimeout = 5000;
        this.timeout = this.defaulttimeout;
    }

    /** Main loop for the Server */
    public void start() throws Exception{
        // Start the receiver
        receiver.start();

        println("\n################### Waiting #########################\n");

        // Process packets queue
        while(true){

            // Wait for anything from receiver or timeout
            if(!receiver.hasPacket()) cdl.await();

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

            if(duration != -2L){
                timeoutthread = new Timeout(this.cdl, duration);
                timeoutthread.start();
            }else{
                println("Still waiting...");
            }
        }

        println("Stopping the server\n");
        //System.out.println(sb.toString());
        if(!quiet) System.out.println("\rOutput MD5 hash: " + Utility.getHash(sb.toString()));
        else System.out.println("MD5 hash of the file: " + Utility.getHash(sb.toString()));

        // Clean up
        this.cleanUp();
    }

    /** Convenience method to fire away TCP packet
     *  @param ack_bit Acknowledgement Bit in packet. True if enabled, otherwise false
     *  @param syn_bit Synchronization Bit in packet. True if enabled, otherwise false
     *  @param fin_bit Finish Bit in packet. True if enabled, otherwise false
     */
    public void sendPacket(boolean ack_bit, boolean syn_bit, boolean fin_bit) throws Exception{
        sendPacket(this.seq_num, this.ack_num, ack_bit, syn_bit, fin_bit);
    }

    /** Convenience method to fire away TCP packet
     *  @param seq_num Sequence Number
     *  @param ack_num Acknowledgement Number
     *  @param ack_bit Acknowledgement Bit in packet. True if enabled, otherwise false
     *  @param syn_bit Synchronization Bit in packet. True if enabled, otherwise false
     *  @param fin_bit Finish Bit in packet. True if enabled, otherwise false
     */
    public void sendPacket(int seq_num, int ack_num, boolean ack_bit, boolean syn_bit, boolean fin_bit) throws Exception{
        // Build the packet
        Packet packet = new Packet(seq_num, ack_num, ack_bit, fin_bit, syn_bit, receiver.getAvailableBuffer(), null, this.mailbox.getLocalAddress(), this.mailbox.getLocalPort());

        // Convert the packet to byte array
        byte[] tcp_packet = packet.toByteArray();

        /*
        // Packet dropper
        int chance = (int)(Math.random() * 5);
        if(chance != 1) mailbox.send(new DatagramPacket(tcp_packet, tcp_packet.length, this.clientAddress, this.clientPort));
        //else System.out.println("&&&&& FAILURE &&&&&");
        */

        // Send the packet
        mailbox.send(new DatagramPacket(tcp_packet, tcp_packet.length, this.clientAddress, this.clientPort));

        // Verbose message
        //print("## SENDING PACKET ##\n" + packet);
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

        // Verbose message
        if(!quiet){
            System.out.print("\n## RECEIVING PACKET ## Seq: " + packet.seq_num + " ack: " + packet.ack_num + " length: " + packet.length + " ");
            if(packet.ack_bit) System.out.print("A ");
            if(packet.syn_bit) System.out.print("S ");
            if(packet.fin_bit) System.out.print("F ");
            System.out.println("\n#");
        }

        // Check if SYN message
        if(packet.syn_bit){
            // Verbose message
            println("################### Synchronization #########################\n#");
            println("# SYN message received. Responding to ACK and sending SYN");

            //if(quiet) System.out.print("Transfer in progress...");

            // Record the client's address and port
            this.clientAddress = packet.senderAddress;
            this.clientPort = packet.senderPort;

            // Respond to client's SYN
            ack_bit = true;

            //Increment ack number - 1 SYN = 1 number
            this.ack_num = this.seq_num + 1;

            // Handshake client with sending SYN
            syn_bit = true;
            this.state = FSM.SYN;
        }

        // Check if ACK message
        if(packet.ack_bit){
            // Verbose message
            println("# ACK received");

            // Recalculate RTT
            recalculateRTT(getTime() - this.last_time);

            // Check for acceptable states
            if(this.state == FSM.SYN || this.state == FSM.ESTABLISHED || this.state == FSM.FIN){
                // Client received N bytes so sender may advance
                this.seq_num = packet.ack_num;

                // Change states if applicable
                if(this.state == FSM.SYN){
                    // Verbose message
                    println("# \n################### Established! #########################\n#");

                    // Mark the state as established
                    this.state = FSM.ESTABLISHED;

                    // Helper value for file pointer
                    file_seq_offset = this.seq_num;
                }
                // Finish state
                if(this.state == FSM.FIN){
                    if(packet.ack_num == this.seq_num){
                        // Verbose message
                        println("# \n################### Finished! #########################\n");

                        // Mark the state as finished
                        this.state = FSM.FIN_ACK;
                    }
                }
            }
        }

        // Check if file message
        if(packet.length != 0){
            if(this.state == FSM.ESTABLISHED){
                // Verbose message
                print("# Data received! ack_num: " + this.ack_num + " last ack: " + last_ack);

                // Recalculate RTT
                recalculateRTT(getTime() - this.last_time);

                // Check if packet is not advanced
                if(packet.seq_num == this.ack_num){
                    // Verbose message
                    println("\n# Packet is in order\n#");

                    // Save old ack
                    this.last_ack = this.ack_num;

                    // Write to buffer
                    for(int i = 0; i < packet.length; i++) sb.append((char)packet.data[i]);

                    // Reset flag
                    this.retransmitcount = 0;

                    // Modify numbers
                    this.ack_num = packet.seq_num + packet.length;
                    this.seq_num = packet.ack_num;

                    // Set the bit
                    ack_bit = true;
                }else if(packet.seq_num <= this.ack_num){
                    // Verbose message
                    println("\n# Packet is older, rewrite\n#");

                    // Undo the write
                    if(packet.seq_num < this.ack_num){
                        int pointer = sb.length() - ((this.ack_num - file_seq_offset) - (packet.seq_num - file_seq_offset));
                        sb.delete(pointer, sb.length());
                    }

                    // Reset flag
                    this.retransmitcount = 0;

                    // Write to buffer
                    for(int i = 0; i < packet.length; i++) sb.append((char)packet.data[i]);

                    // Modify numbers
                    this.ack_num = packet.seq_num + packet.length;
                    this.seq_num = packet.ack_num;

                    // Set the bit
                    ack_bit = true;
                }else{
                    if(packet.seq_num > this.ack_num){
                        // Verbose message
                        println("\n# Packet is beyond the order - missing packet in between");
                    }else{
                        // Verbose message
                        println("\n# Packet is behind the order - stray packet");
                    }
                    // Flag the retransmit - I used retransmitcount to make the transmit less spammy
                    this.retransmitcount++;
                    print("## retransmit: " + this.retransmitcount);
                    if((retransmitcount % 5 == 0)){
                        // Verbose message
                        print("## Fast retransmit - SENDING X2 PACKETS ## ack_num: " + this.ack_num + " - ");

                        // Store the last ack
                        this.last_ack = this.ack_num;

                        // Send 2 same packets
                        sendPacket(this.seq_num, this.ack_num, true, syn_bit, fin_bit);
                        sendPacket(this.seq_num, this.ack_num, true, syn_bit, fin_bit);

                        // We're not interested in any packets stored in the receiver
                        receiver.clearBuffer();

                        // Double the timeout
                        this.timeout += this.timeout + ((this.timeout == 0) ? 2 : 0);
                        this.timeout = Math.min(this.timeout, this.defaulttimeout);
                        return this.timeout;
                    }
                }
            }
        }

        // Check if FIN message
        if(packet.fin_bit){
            // Verbose message
            if(!quiet){
                System.out.println("# FIN message received. Responding to ACK and sending FIN");
                System.out.println("## SENDING PACKET ## ack_num: " + this.ack_num + 1 + " A F");
            }

            // End connection
            fin_bit = true;
            this.state = FSM.FIN;

            // Respond to client's FIN
            ack_bit = true;

            //Increment ack number - 1 FIN = 1 number
            this.ack_num = this.ack_num + 1;
        }

        // Send packet where appropriate
        if(syn_bit || ack_bit || fin_bit) sendPacket(ack_bit, syn_bit, fin_bit);

        // Store RTT time
        this.last_time = getTime();

        // Exit if FIN is already acked
        if(this.state == FSM.FIN_ACK) return -1;

        return this.timeout;
    }

    /** Process the time out call
     *  @return time out duration
     */
    public int processTimeout() throws Exception{
        // Verbose message
        println("\n##Timed out##");

        // Double the timeout
        this.timeout += this.timeout + ((this.timeout == 0) ? 2 : 0);
        this.timeout = Math.min(this.timeout, this.defaulttimeout);

        // No ACK from the client for SYN
        if(this.state == FSM.SYN){
            // Verbose message
            println("# SYN packet sent by server timed out. Resending SYN");

            // Send packet with SYN and ACK
            sendPacket(true, true, false);

            // Timeout
            return this.timeout;
        }

        // Established state
        if(this.state == FSM.ESTABLISHED){
            // retransmitcount is used to reduce the spam from retransmit
            this.retransmitcount++;
            // Verbose message
            print("## retransmit: " + this.retransmitcount);
            if((retransmitcount % 5 == 0) && this.ack_num != this.last_ack){
                // Verbose message
                print("#  NO PACKETS FAST RETRANSMIT!\n#");
                print("## SENDING X2 PACKETS ## ack_num: " + this.ack_num + " - ");

                // Send 2 same packets
                sendPacket(this.seq_num, this.ack_num, true, false, false);
                sendPacket(this.seq_num, this.ack_num, true, false, false);

                // We're not interested in any packets stored in the receiver
                receiver.clearBuffer();
            }
            return this.timeout;
        }

        // Finish state
        if(this.state == FSM.FIN){
            // Verbose message
            println("FIN packet sent by server timed out. Assuming they got the packet. Shutting down the server");

            // Stop the server
            return -1;
        }

        println("processTimeout method ends");
        return -1;
    }

    /** Clean up method - cleans up everything */
    public void cleanUp() throws Exception{
        //ra.close();
        receiver.close();
        receiver.interrupt();
        receiver = null;
        timeoutthread = null;
        cdl = null;
        mailbox.close();
    }
}
