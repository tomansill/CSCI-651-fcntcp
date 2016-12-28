/* @author Thomas Ansill
 * CSCI-651-03
 * Project 2
 */
import java.io.File;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.io.EOFException;
import java.io.ByteArrayOutputStream;

/** Convenience class for hashing */
public class Utility{
    final protected static char[] hexArray = "0123456789abcdef".toCharArray();

    /** Gets hash from a file
     *  @param file File to get hash from
     *  @return hash digest
     */
    public static String getFileHash(File file) throws Exception{
        //StringBuffer sb = new StringBuffer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RandomAccessFile ra = new RandomAccessFile(file, "r");

        // Read everything from the file
        try{
            while(true) baos.write(ra.readByte());
        }catch(EOFException e){}

        ra.close();
        return getHash(baos.toByteArray());
    }

    /** Converts byte array to hexidecimal string
     *  Code used from the StackOverflow page. The code is authored by maybeWeCouldStealAVan on
     *  https://stackoverflow.com/questions/9655181/how-to-convert-a-byte-array-to-a-hex-string-in-java#9855338
     *  September 19, 2014 at 20:32. Date accessed: December 8, 2016
     *
     *  @param array byte array
     *  @return hexidecimal string
     */
    public static String byteArrayToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }



    /** Gets hash from a string
     *  @param clear String to get hash digest from
     *  @return hash digest
     */
    public static String getHash(String clear) throws Exception{
        byte[] buffer = new byte[clear.length()];
        for(int i = 0; i < buffer.length; i++) buffer[i] = (byte)clear.charAt(i);
        return getHash(buffer);
    }

    /** Gets hash from a string
     *  @param byte array to get hash digest from
     *  @return hash digest
     */
    public static String getHash(byte[] buff) throws Exception{
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] buffer = digest.digest(buff);

        return byteArrayToHex(buffer);
    }
}
