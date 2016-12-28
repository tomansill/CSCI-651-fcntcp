/* @author Thomas Ansill
 * CSCI-651-03
 * Project 2
 */

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.InetSocketAddress;

/* Convenience Class to handle UDP datagrams

UDP Datagram
32 bit width
16 bit source port
16 bit dest port
16 bit length
16 bit checksum
1500 - 20 - 24 =
TCP Datagram - 576 bytes - IP header = 556 - 8 bytes UDP header = 548 bytes and 548 - 16 bytes = 532 bytes of data
32 bit width
16 bit source port // automatic by java?
16 bit dest port // automatic by java?
32 bit sequence number
32 bit acknowledgement number
1 bit ACK bit
1 bit SYN bit
1 bit FIN bit
13 bits of nothing
16 bit length
16 bit window size
16 bit checksum
*/

public class Packet{
    /** Max length for data to fit in TCP packet */
    public static final int MAX_DATA_LENGTH = 532;

    /** Max length for data to fit in UDP packet */
    public static final int MAX_PACKET_LENGTH = 556;


    /** Sequence number on TCP header */
    public int seq_num;
    /** Acknowledgement number on TCP header */
    public int ack_num;
    /** Acknowledgement bit on TCP header */
    public boolean ack_bit;
    /** Finish bit on TCP header */
    public boolean fin_bit;
    /** Synchronize bit on TCP header */
    public boolean syn_bit;
    /** Receiving Window value on TCP header */
    public short window;
    /** Data on TCP packet */
    public byte[] data;
    /** Data length value on TCP header */
    public int length;
    /** Sender address on UDP header */
    public InetAddress senderAddress;
    /** Sender port on UDP header */
    public int senderPort;

    /** Constructor with data
     *  @param seq_num Sequence number
     *  @param ack_num Acknowledgement number
     *  @param ack_bit Acknowledgement bit
     *  @param fin_bit Finish bit
     *  @param syn_bit Synchronize bit
     *  @param window Receiving Window value
     *  @param data Data
     *  @param senderAddress Sender's IP address
     *  @param senderPort Sender's port number
     */
    public Packet(int seq_num, int ack_num, boolean ack_bit, boolean fin_bit, boolean syn_bit, short window, byte[] data, InetAddress senderAddress, int senderPort){
        this.seq_num = seq_num;
        this.ack_num = ack_num;
        this.ack_bit = ack_bit;
        this.fin_bit = fin_bit;
        this.syn_bit = syn_bit;
        this.window = window;
        this.data = data;
        if(data == null) this.length = 0;
        else this.length = data.length;
        this.senderAddress = senderAddress;
        this.senderPort = senderPort;
    }

    /** Constructor with no data
     *  @param seq_num Sequence number
     *  @param ack_num Acknowledgement number
     *  @param ack_bit Acknowledgement bit
     *  @param fin_bit Finish bit
     *  @param syn_bit Synchronize bit
     *  @param window Receiving Window value
     *  @param length length of data
     *  @param senderAddress Sender's IP address
     *  @param senderPort Sender's port number
     */
    public Packet(int seq_num, int ack_num, boolean ack_bit, boolean fin_bit, boolean syn_bit, short window, int length, InetAddress senderAddress, int senderPort){
        this.seq_num = seq_num;
        this.ack_num = ack_num;
        this.ack_bit = ack_bit;
        this.fin_bit = fin_bit;
        this.syn_bit = syn_bit;
        this.window = window;
        this.data = null;
        this.length = length;
        this.senderAddress = senderAddress;
        this.senderPort = senderPort;
    }

    /** Converts a packet to String representation
     *  @return String representation of this packet
     */
    public String toString(){
        StringBuilder sb = new StringBuilder();
        //sb.append("Sender Address: ");
        //sb.append(senderAddress);
        //sb.append("\n");
        sb.append("Sender Port: ");
        sb.append(senderPort);
        sb.append("\n");
        sb.append("Sequence number: ");
        sb.append(this.seq_num);
        sb.append("\n");
        sb.append("Acknowledgement number: ");
        sb.append(this.ack_num);
        sb.append("\n");
        sb.append("ACK Bit: ");
        sb.append(this.ack_bit);
        sb.append("\n");
        sb.append("FIN Bit: ");
        sb.append(this.fin_bit);
        sb.append("\n");
        sb.append("SYN Bit: ");
        sb.append(this.syn_bit);
        sb.append("\n");
        sb.append("Data Length: ");
        if(this.data != null) sb.append(this.data.length);
        else sb.append(0);
        sb.append("\n");
        sb.append("Window size: ");
        sb.append(this.window);
        sb.append("\n");

        return sb.toString();
    }

    /** Converts the packet to byte array
    *   @return byte array form of packet
    */
    public byte[] toByteArray() throws IOException{
        //Data needs to be under 548 bytes to survive the routing in the network as specified in RFC
        if(this.data != null && this.data.length > MAX_PACKET_LENGTH) throw new IOException("Data is too large!");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        int length = 0;
        if(data != null) length = this.data.length;
        char checksum = calculateCRC16(seq_num, ack_num, ack_bit, fin_bit, syn_bit, window, data, length, senderAddress, senderPort);

        baos = new ByteArrayOutputStream();
        dos = new DataOutputStream(baos);
        dos.writeInt(this.seq_num);
        dos.writeInt(this.ack_num);
        dos.writeBoolean(this.ack_bit);
        dos.writeBoolean(this.fin_bit);
        dos.writeBoolean(this.syn_bit);
        for(int i = 0; i < 13; i++) dos.writeBoolean(false); //Other 13 bits remains unused
        if(this.data != null) dos.writeShort(this.data.length);
        else dos.writeShort(0);
        dos.writeShort(this.window);
        dos.writeChar(checksum); //Checksum
        if(this.data != null) dos.write(this.data, 0, data.length); //Data

        byte[] packet = baos.toByteArray();

        assert(packet.length <= 548);

        return packet;
    }

    /** Method to build packet from byte array
     *  @param packet packet in byte array
     *  @param length Length of packet
     *  @param address IP address of sender
     *  @param port port number of sender
     */
    public static Packet buildPacketFromByteArray(byte[] packet, int length, InetAddress address, int port) throws Exception{
        if(packet.length < 16 && length < 16) throw new Exception("Corrupted Packet");
        if(packet.length < length) throw new Exception("Corrupted Packet");

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(packet, 0, length));

        int seq_num = dis.readInt();
        int ack_num = dis.readInt();
        boolean ack_bit = dis.readBoolean();
        boolean fin_bit = dis.readBoolean();
        boolean syn_bit = dis.readBoolean();
        for(int i = 0; i < 13; i++) dis.readBoolean(); //Skip 13 bits
        length = dis.readShort();
        short window = dis.readShort();
        char checksum = dis.readChar();
        byte[] data = new byte[length];
        dis.read(data, 0, length);

        //Checksum the packet
        char checksum_packet = calculateCRC16(seq_num, ack_num, ack_bit, fin_bit, syn_bit, window, data, length, address, port);

        Packet pkt = new Packet(seq_num, ack_num, ack_bit, fin_bit, syn_bit, window, data, address, port);

        //Check if packet is corrupted
        if(checksum_packet != checksum) throw new Exception("Corrupted Packet");

        return pkt;
    }

    /** Checksum function using CRC16
     *  @param seq_num Sequence number
     *  @param ack_num Acknowledgement number
     *  @param ack_bit Acknowledgement bit
     *  @param fin_bit Finish bit
     *  @param syn_bit Synchronize bit
     *  @param window Receiving Window value
     *  @param data Data
     *  @param length Data length
     *  @param senderAddress Sender's IP address
     *  @param senderPort Sender's port number
     */
    public static char calculateCRC16(int seq_num, int ack_num, boolean ack_bit, boolean fin_bit, boolean syn_bit, short window, byte[] data, int length, InetAddress address, int port){
        int j;
        int crc = 0;
        for(int i = 0; i < length; i++){
            for (j = 0x80; j != 0; j >>= 1) {
                if((crc & 0x8000) != 0)  crc = (char)((crc << 1) ^ 0x8005);
                else crc = (char)(crc << 1);
                if ((data[i] & j) != 0) crc ^= 0x8005;
            }
        }
        //System.out.println("dcrc: " + crc);
        //int hash = address.hashCode();
        int hash = 0;
        hash ^= seq_num;
        hash ^= ack_num;
        hash += (ack_bit ? 1 : 0);
        hash += (fin_bit ? 1 : 0);
        hash += (syn_bit ? 1 : 0);
        hash ^= window << 16;
        hash ^= port;
        crc ^= hash;
        //System.out.println("crc: " + crc);
        return (char)crc;
    }

    /** Convenience method to display byte in binary format
     *  @param byte
     *  @return String representation of byte in binary format
     */
    private static String byteToString(byte b) {
        byte[] masks = { -128, 64, 32, 16, 8, 4, 2, 1 };
        StringBuilder sb = new StringBuilder();
        for(byte m : masks){
            if ((b & m) == m) sb.append('1');
            else sb.append('0');
        }
        return sb.toString();
    }

    /** Show full binary packet with layouts
     *  @param data packet
     *  @param length of packet
     *  @return string representation of binary packet
     */
    public static String getPacketString(byte[] data, int length){
        assert(data.length >= 16);
        StringBuilder sb = new StringBuilder();
        sb.append("  0                   1                   2                   3\n");
        sb.append("  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1\n");
        sb.append(" +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+\n");
        int packet_index = 0;

        //Write sequence number
        sb.append(" |");
        StringBuilder bits = new StringBuilder();
        for(;packet_index < 4; packet_index++) bits.append(byteToString(data[packet_index]));
        sb.append(bits.toString().replace("", " ").trim());
        sb.append("|\n");
        sb.append(" +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+\n");

        //Write acknowledgement number
        sb.append(" |");
        bits = new StringBuilder();
        for(;packet_index < 8; packet_index++) bits.append(byteToString(data[packet_index]));
        sb.append(bits.toString().replace("", " ").trim());
        sb.append("|\n");
        sb.append(" +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+\n");

        //Write flags bits
        sb.append(" |");
        bits = new StringBuilder();
        for(;packet_index < 10; packet_index++) bits.append(byteToString(data[packet_index]));
        sb.append(bits.toString().replace("", " ").trim());

        //Write data length number
        sb.append("|");
        bits = new StringBuilder();
        for(;packet_index < 12; packet_index++) bits.append(byteToString(data[packet_index]));
        sb.append(bits.toString().replace("", " ").trim());
        sb.append("|\n");
        sb.append(" +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+\n");

        //Write window number
        sb.append(" |");
        bits = new StringBuilder();
        for(;packet_index < 14; packet_index++) bits.append(byteToString(data[packet_index]));
        sb.append(bits.toString().replace("", " ").trim());

        //Write checksum number
        sb.append("|");
        bits = new StringBuilder();
        for(;packet_index < 16; packet_index++) bits.append(byteToString(data[packet_index]));
        sb.append(bits.toString().replace("", " ").trim());
        sb.append("|\n");
        sb.append(" +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+\n");

        //Write data
        if(packet_index <= length-16){
            sb.append(" |");
            bits = new StringBuilder();
            int bytes = 0;
            for(;packet_index < length-16; packet_index++){
                if(packet_index != 16 && (packet_index % 4) == 0){
                    sb.append(bits.toString().replace("", " ").trim());
                    bits = new StringBuilder();
                    sb.append("|\n");
                    sb.append(" +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+\n");
                    sb.append(" |");
                }
                bits.append(byteToString(data[packet_index]));
            }
            sb.append(bits.toString().replace("", " ").trim());
            //Pad the remaining bytes with 0z
            if((packet_index % 4) != 0) for(int i = 0; i < (4 - (packet_index % 4)); i++) sb.append(" 0 0 0 0 0 0 0 0");
            sb.append("|\n");
            sb.append(" +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+\n");
        }

        //Finished
        return sb.toString();
    }
}
