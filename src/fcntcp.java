/* @author Thomas Ansill
 * CSCI-651-03
 * Project 2
 */
import java.io.File;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashSet;

/** The main class - used only for parsing the arguments and running the appropraite actions
 *  Usage: fcntcp -{c,s} [options] [server address] port
 */
public class fcntcp{
    /** Display Usage function with no error message */
    private static void usage(){ usage(null); }
    /** Display Usage function
     *  @param message Message to display on the console. Null or empty string
     *  will cause the function to skip the printing of the additional message
     */
    private static void usage(String message){
        if(message != null && message.length() != 0){
            System.err.print("Error: ");
            System.err.println(message);
        }
        System.err.println("Usage: fcntcp -{c,s} [options] [server address] port");
        System.exit(1); //1 to indicate an error
    }
    /** Usage: fcntcp -{c,s} [options] [server address] port
     *  @param args Commandline arguments
     */
    public static void main(String[] args){
        if(args.length < 2) usage();
        HashSet<String> options = new HashSet<String>(); //Used to catch duplicate option flags
        boolean client = false;
        if(args[0].toLowerCase().equals("-c")) client = true;
        else if(args[0].toLowerCase().equals("-s")) client = false;
        else usage("Client/Server choice not understood, please choose between '-s' or '-c'.");
        int port = -1; //No default port available
        boolean quiet = false; //Defaults to false
        boolean uncaught = true; //Flag used to indicate an incorrect or leading argument parts
        File file = null;
        int timeout = 1000; //Default to 1000 milliseconds (1 second)
        InetAddress address = null;
        for(int i = 1; i < args.length; i++){
            //Attempt File
            if(uncaught && (args[i].equals("-f") || args[i].equals("--file"))){
                if(!client) usage();
                if(options.contains("file")) usage("Duplicate file option");
                i++; //Advance the args pointer
                if(i >= args.length) usage(); //Check the bounds
                //Check on the file
                file = new File(args[i]);
                if(!file.exists()) usage("The specified file does not exist.");
                if(!file.isFile()) usage("The specified file is not a normal file. (perhaps a directory?)");
                if(!file.canRead()) usage("The user does not have a read privilege to read the specified file.");
                options.add("file"); //everything is OK, add to record that file option is defined
                uncaught = false;
            }
            //Attempt Timeout
            if(uncaught && (args[i].equals("-t") || args[i].equals("--timeout"))){
                if(!client) usage();
                if(options.contains("timeout")) usage("Duplicate timeout option");
                i++;
                if(i >= args.length) usage(); //Check the bounds
                try{
                    timeout = Integer.parseInt(args[i]);
                    if(timeout < 1) usage("The value for -t option cannot be less than 1!");
                }catch(Exception e){
                    usage("The value for -t option must be a integer!");
                }
                options.add("timeout"); //everything is OK, add to record that file option is defined
                uncaught = false;
            }
            //Attempt Quiet
            if(uncaught && (args[i].contains("-q") | args[i].contains("--quiet"))){
                if(options.contains("quiet")) usage("Duplicate quiet option");
                quiet = true;
                options.add("quiet");
                uncaught = false;
            }
            //Attempt address
            if(client && uncaught && !options.contains("address") && args[i].matches("(\\d+\\.\\d+\\.\\d+\\.\\d+)|(\\w*|\\d*)+\\.*(\\w*|\\d*)+\\.(\\w*|\\d*)+")){ //Regex attempts to match input string with "subdomainname"."domainname"."extension" or x.x.x.x
                try{
                    address = InetAddress.getByName(args[i]); //Attempt
                    if(address == null) usage("Cannot verify the server address: '" + args[i] + "'");
                    options.add("address");
                }catch(UnknownHostException e){
                    usage("Cannot verify the server address: '" + args[i] + "'");
                }
                uncaught = false;
            }
            //Attempt port
            if(uncaught && !options.contains("port")){
                try{
                    port = Integer.parseInt(args[i]); //If fail, probably something else, not a port
                    options.add("port");
                    uncaught = false;
                }catch(Exception e){} //Do nothing
            }
            if(uncaught) usage(); //If uncaught then this piece of argument is invalid
            else uncaught = true; //Changes to true and continue the argument checking on the next loop
        }
        if(client){
            /*
            System.out.println("Client");
            System.out.println("File: " + file);
            System.out.println("Timeout: " + timeout);
            System.out.println("Address: " + address);
            System.out.println("Port: " + port);
            System.out.println("Quiet: " + quiet);
            */
            if(file == null) usage("File input required.");
            if(address == null) usage("Server Address input required.");
            if(port == -1) usage("Server Port required.");
            try{
                new Client(address, port, file, timeout, quiet).start();
            }catch(Exception se){
                System.err.println("Error: " + se.getMessage());
                se.printStackTrace();
                System.exit(1);
            }
        }else{
            /*
            System.out.println("Server");
            System.out.println("Port: " + port);
            System.out.println("Quiet: " + quiet);
            */
            if(port == -1) usage("Port required.");
            try{
                new Server(port, quiet).start();
            }catch(Exception e){
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        }
    }
}
