/**
 * Ricart Agrawala Mutual Exclusion Implementation. This implementation is
 * Dynamic in nature. Dynamic here means that any process can enter the system
 * or leave the system at any time without causing any disturbance in the smooth
 * functioning of the system. This implementation Consists of 3 components: 
 * 1- Bootstrap server. Important to allow new processes to enter the system, 
 * allow processes to smoothly exit the system and allow a Critical Section 
 * Machine to bind with the system.
 * 2- Critical Section Machine. All the processes access the Critical Section 
 * present in this machine. The entry of a process, the updating of the shared 
 * resource and the exit of a process can be easily inferred with the help of 
 * visualization.
 * 3- The Process. The processes have transaction events going on in the 
 * background where they occasionally communicate with each other. These 
 * processes schedule a CS Access request randomly and follow the Ricart
 * Agrawala Algorithm to ensure mutual exclusion with respect to CS Access to 
 * update the Shared Resource.
 *
 * Usage: 
 * Bootstrap:                   java RicAg -b 
 * Critical Section Machine:    java RicAg -cs [bootstrap IP] 
 * Process:                     java RicAg [bootstrap IP]
 * 
 * @version April 11th, 2016.
 * @author Aditya Advani
 */
//package ricag;

import java.io.Serializable;
import static java.lang.Thread.sleep;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.AccessException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class instantiates a Bootstrap Server, a Critical Section Machine and up
 * to [max_allowed_processes] process that communicate with each other to
 * transfer funds and request to enter the Critical Section to update the shared
 * resource in mutual exclusion
 *
 * @author Aditya Advani
 */
public class RicAg extends UnicastRemoteObject implements RIF, Serializable, Runnable {

    // System limitation variables
    static int max_allowed_processes = 100; //max processes that the system will allow
    static int timeoutLimit = 8500; //timeout after process not getting CS Access after defined time frame from the point of requesting
    static int max_processes_shown = 0; //number of max vector clock elements that need to be processed curretly

    //information about current node
    static String NodeName = ""; //Name of the process
    static String NodeIP = ""; //IP address of the process
    static int NodeID = 0; //ID of the process
    static int port = 7394; //Portnumber to run the registry on
    static Registry r; //Registry instance
    static RicAg ra; //Outer Class object instance
    static int clock[]; //vector clock of the process
    static int counter = 0; //CS Access permission reply counter
    static ArrayList<String> queue; //Pending request queue of the process
    static ArrayList<Integer> EmptyClocks; //List of empty IDs occuring due to processes exiting
    static int bal = 1000; //Startinf balance in $ of current process
    static final Object o = new Object(); //Syncronization object
    static String bootstrap = ""; //IP of the bootstrap server

    //CS Access flags
    static boolean WANTED = false; //States process is still waiting for CS Access permission replies from few nodes or another process is still in the CS
    static boolean HELD = false; //States current process is in the CS
    static boolean RELEASED = true; //States the current process had left the CS and will now reply to every CS Access Request
    static boolean InCS = false; //flag used for Shared Resource update viualization.

    // String IP Address of the machine having the Critical Section
    static String CSMachine = ""; //IP address of the Machine having the Critical Section

    //Map of all the running processes
    static HashMap<Integer, String> process = new HashMap<>(); //Mapping of process ID - Process IP
    static HashMap<String, String> processName = new HashMap<>(); //Mapping of process IP - Process Name
    static HashMap<String, String> awaitingReply = new HashMap<>(); //Mapping of pending replies from processes requested for CS Access permission

    /**
     * Default constructor
     *
     * @throws java.rmi.RemoteException
     */
    public RicAg() throws RemoteException {
        super();
    }

    @Override
    /**
     * This method updates the current process map
     */
    public void updateMap(HashMap<Integer, String> p) throws RemoteException {
        process = p;
        if (process.size() > max_processes_shown) {
            max_processes_shown = process.size();
        }
    }

    @Override
    /**
     * This method creates an IP-name mapping for current processes in the
     * system
     */
    public void enhanceMap(HashMap<String, String> ep) throws RemoteException {
        processName = ep;
    }

    /**
     * Generates a random event from amongst withdraw, deposit and transfer of
     * money
     *
     * @return event_id 1=withdraw, 2=deposit, 3=transfer
     */
    public static int getEvent() {
        int event_id = -1;

        int i = (int) (Math.random() * 100);
        if (i >= 0 && i <= 33) {
            event_id = 1;
        } else if (i >= 34 && i <= 66) {
            event_id = 2;
        } else if (i >= 67 && i <= 100) {
            event_id = 3;
        }
        return event_id;
    }

    /**
     * Display the vector clock and mark the clock corresponding to the current
     * machine
     */
    public static void displayClock() {
        System.out.print("Clock: ");
        for (int i = 0; i < max_processes_shown; i++) {
            if (i == (NodeID - 1)) {
                System.out.print("*");
            }
            System.out.print(clock[i]);
            if (i == (NodeID - 1)) {
                System.out.print("*");
            }
            System.out.print("\t");
        }
        System.out.println("");
    }

    @Override
    /**
     * Called on the CS Machine. This method allows a process to be in the
     * critical section for a random time up to 1.5s. As this method is not
     * synchronized, it can allow multiple process to enter at once. Mutual
     * exclusion mechanism will prevent this and allow only one process to be in
     * the CS at any point of time
     */
    public void enterCS(String node) throws RemoteException {
        System.out.println("\n\n\n");
        System.out.println(node + " in Critical Section!");
        System.out.println("Working on Shared Resource");
        InCS = true;
        Thread updtSR = new Thread(new UpdatingSR());
        updtSR.start();
        int randomWaitTime = (int) (Math.random() * 1500);
        try {
            Thread.sleep(randomWaitTime);
            InCS = false;
        } catch (InterruptedException ex) {
            Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
        }
        System.out.println(node + " left Critical Section");
    }

    @Override
    /**
     * Called on the requesting machine. This method is used for delivering
     * responses from other machines that were requested for permission to enter
     * CS. When the current machine has responses from all the other processes
     * that are alive, it will enter the CS.
     */
    public void listen(int[] otherclock, String IP) throws RemoteException {
        counter++;
        for (int i = 0; i < max_processes_shown; i++) {
            if (otherclock[i] > clock[i]) {
                clock[i] = otherclock[i];
            }
        }

        if (!awaitingReply.isEmpty() && awaitingReply.containsKey(NodeIP)) {
            awaitingReply.remove(NodeIP);
        }
        if (counter == (process.size() - 1)) {
            HELD = true;

            try {
                r = LocateRegistry.getRegistry(CSMachine, port);
                RIF CSM = (RIF) r.lookup("process");
                CSM.enterCS(NodeName);
            } catch (NotBoundException | AccessException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }

            HELD = false;
            WANTED = false;
            RELEASED = true;
            System.out.println("\n**********");
            System.out.println("Ended Successfully!!");
            System.out.println("**********\n");
            if (!queue.isEmpty()) {
                System.out.println("Sending remaining requeests now...");
                for (int i = 0; i < queue.size(); i++) {
                    try {
                        r = LocateRegistry.getRegistry(queue.get(0), port);
                        RIF otherNode = (RIF) r.lookup("process");
                        clock[NodeID - 1]++;
                        otherNode.listen(clock, NodeIP);
                        queue.remove(0);
                    } catch (NotBoundException | AccessException ex) {
                        Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
            awaitingReply.clear();
            displayClock();
        }
    }

    @Override
    /**
     * Called on other machines. This method is called from the current process
     * on the other processes to request for permission to enter the CS. If the
     * other process is in RELEASED state, it will reply positively. If the
     * other process is in the HELD state, it will simply enqueue the requesting
     * process. If the other process in in WANTED state, appropriate actions
     * based on the Vector clock and the Process ID will be performed to ensure
     * deadlock prevention.
     *
     * @param node IP of requesting process
     * @param otherclock vector clock of requesting process
     * @param id NodeID of requesting process
     */
    public void ask(String node, int[] otherclock, int id) throws RemoteException {
        clock[NodeID - 1]++;
        if (RELEASED) {
            try {
                r = LocateRegistry.getRegistry(node, port);
                RIF otherNode = (RIF) r.lookup("process");
                otherNode.listen(clock, NodeIP);
            } catch (NotBoundException | AccessException ex) {
                ex.printStackTrace();
            }
        } else {
            if (HELD) {
                queue.add(node);
                System.out.println("\nProcess in HELD state, so wont reply to " + processName.get(node) + " right now.");
            } else if (WANTED) {
                System.out.println("\nProcess in WANTED state.");
                if (!awaitingReply.isEmpty() && !awaitingReply.containsKey(node)) {
                    queue.add(node);
                    System.out.println("since already got reply from " + processName.get(node) + ", process will enqueue.");
                } else {
                    System.out.println("since both are requesting, will compare vector clock.");
                    boolean isSmall = false;
                    boolean isLarge = false;
                    boolean isIndeterministic = false;
                    for (int i = 0; i < max_processes_shown; i++) {
                        if (otherclock[i] > clock[i]) {
                            isSmall = true;
                        }
                        if (otherclock[i] < clock[i]) {
                            isLarge = true;
                        }
                        if (isSmall && isLarge) {
                            isIndeterministic = true;
                            break;
                        }
                    }
                    if (isIndeterministic) {
                        if (NodeID > id) {
                            try {
                                System.out.println("Vector Indeterministic, so sending based on PID.");
                                r = LocateRegistry.getRegistry(node, port);
                                RIF otherNode = (RIF) r.lookup("process");
                                otherNode.listen(clock, NodeIP);
                            } catch (NotBoundException | AccessException ex) {
                                ex.printStackTrace();
                            }
                        }
                    } else if (isLarge) {
                        try {
                            System.out.println("Vector deterministic, so sending based on vector clock.");
                            r = LocateRegistry.getRegistry(node, port);
                            RIF otherNode = (RIF) r.lookup("process");
                            otherNode.listen(clock, NodeIP);
                        } catch (NotBoundException | AccessException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    @Override
    /**
     * Called o Transfer receiving process. This method ensures that the
     * transfer receiving process gets the transferred amount without
     * concurrency issues.
     *
     * @param amt the transferred amount
     * @param otherclock the vector clock of the transferring process
     * @param Node the Name of the transferring process
     */
    public void sendAmount(int amt, int[] otherclock, String Node) throws RemoteException {
        synchronized (o) {
            //balance += transferred amount
            bal += amt;

            for (int k = 0; k < max_processes_shown; k++) {
                if (clock[k] < otherclock[k]) {
                    clock[k] = otherclock[k];
                }
            }

            //clock++;
            clock[NodeID - 1]++;

            System.out.println("\n\n\nReceived a transfer of $" + amt + " from " + Node);
            System.out.println("after receiving the transfer," + NodeName + "'s balance is: $" + bal);
            System.out.print("Current vector clock at " + NodeName + " is:\n");
            displayClock();
        }
    }

    /**
     * This method is called on the current process when a new request to enter
     * the CS is scheduled. This method sends out a request for permission to
     * enter the CS to every other process that is alive. If there are no other
     * processes in the system, the current node itself enters the Critical
     * Section with no permissions required.
     *
     * @param Node IP of the node scheduling CS Access request
     */
    public static void request(String Node) {
        String node;
        clock[NodeID - 1]++;
        if (process.size() == 1) {
            HELD = true;

            try {
                r = LocateRegistry.getRegistry(CSMachine, port);
                RIF CSM = (RIF) r.lookup("process");
                CSM.enterCS(NodeName);
            } catch (NotBoundException | AccessException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            } catch (RemoteException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }

            HELD = false;
            WANTED = false;
            RELEASED = true;
            System.out.println("\n**********");
            System.out.println("Ended Successfully!!");
            System.out.println("**********\n");
            if (!queue.isEmpty()) {
                System.out.println("Sending remaining requeests now...");
                for (int i = 0; i < queue.size(); i++) {
                    try {
                        r = LocateRegistry.getRegistry(queue.get(0), port);
                        RIF otherNode = (RIF) r.lookup("process");
                        clock[NodeID - 1]++;
                        otherNode.listen(clock, NodeIP);
                        queue.remove(0);
                    } catch (NotBoundException | AccessException ex) {
                        Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (RemoteException ex) {
                        Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
            awaitingReply.clear();
            displayClock();
        } else {
            for (int i = 1; i <= max_processes_shown; i++) {
                if (i == NodeID || EmptyClocks.contains(i)) {
                    continue;
                }
                node = process.get(i);
                try {
                    r = LocateRegistry.getRegistry(node, port);
                    RIF otherNode = (RIF) r.lookup("process");
                    otherNode.ask(NodeIP, clock, NodeID);
                } catch (NotBoundException | AccessException ex) {
                    ex.printStackTrace();
                } catch (RemoteException ex) {
                    Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    @Override
    /**
     * This is the default run run method of the system. It runs in a loop and
     * schedules a new CS Access request randomly between every 10-15 seconds.
     */
    public void run() {
        //schedule new requests
        while (true) {
            //random time to wait before requesting for CS Access (10000 <= wait <= 15000)
            int randomWaitTime = 10000 + (int) (Math.random() * 5000);
            try {
                Thread.sleep(randomWaitTime);
            } catch (InterruptedException ex) {
                System.out.println("*********************************************");
                System.out.println("Exception while Random wait at Scheduler Run.");
                System.out.println("*********************************************");
            }
            System.out.println("\n\n********************\nNew request generated for accessing CS\n********************\n");
            counter = 0;
            RELEASED = false;
            WANTED = true;
            Thread time = new Thread(new timeout());
            time.start();
            for (String k : processName.keySet()) {
                awaitingReply.put(k, processName.get(k));
            }
            request(NodeIP);

        }
    }

    /**
     * This nested class is used to create a new runnable thread to handle the
     * transactions.
     */
    public static class Transactions implements Runnable {

        /**
         * This run method performs a new event every 5 seconds. The event
         * directly affects the balance amount of the process as thy can either
         * withdraw, deposit or transfer the money to another process with a
         * randomly selected amount not greater than $100. Care is taken to
         * check if the process to be sent to is alive and if the funds are
         * sufficient during withdraw and transfer events. This Thread runs
         * within a synchronization block to avoid any concurrent updated on the
         * balance due to unexpected transfer receive events.
         */
        public void run() {
            int event, amt, temp_process_id;
            String op = "", temp_process_ip = "";
            while (true) {
                try {
                    synchronized (o) {
                        //updating clock
                        clock[NodeID - 1]++;

                        System.out.println("\n\n");

                        //get random event
                        do {
                            event = getEvent();
                        } while (event == 3 && process.size() == 1);
                        //if event = withdraw
                        if (event == 1) {
                            op = "withraw";
                            //get random amount to withdraw between 0 and 100
                            amt = (int) (Math.random() * 100);
                            if ((bal - amt) >= 0) {
                                bal -= amt;
                                System.out.println("withdrew $" + amt + " from " + NodeName);
                            } else {
                                System.out.println("Cannot complete transaction, not enough funds.");
                            }

                        }

                        //if event = deposit
                        if (event == 2) {
                            op = "deposit";
                            //get random amount to deposit between 0 and 100
                            amt = (int) (Math.random() * 100);
                            bal += amt;
                            System.out.println("deposited $" + amt + " to " + NodeName);
                        }

                        //if event = transfer
                        if (event == 3) {
                            op = "transfer";
                            //get random amount to transfer between 0 and 100
                            amt = (int) (Math.random() * 100);
                            double rangestep = (max_allowed_processes / (double) max_processes_shown);

                            //get random transfer recepient other than self
                            do {
                                temp_process_id = (int) (Math.random() * max_allowed_processes);
                                for (int i = 1; i <= max_processes_shown; i++) {
                                    if (temp_process_id >= (rangestep * (i - 1)) && temp_process_id < (rangestep * i)) {
                                        temp_process_id = i;
                                        temp_process_ip = process.get(temp_process_id);
                                        break;
                                    }
                                }
                            } while (temp_process_id == NodeID || EmptyClocks.contains(temp_process_id));

                            try {
                                if ((bal - amt) >= 0) {
                                    r = LocateRegistry.getRegistry(temp_process_ip, port);
                                    RIF pif = (RIF) r.lookup("process");
                                    pif.sendAmount(amt, clock, NodeName);
                                    bal -= amt;
                                    System.out.println("transferred $" + amt + " from " + NodeName + " to " + processName.get(temp_process_ip));
                                } else {
                                    System.out.println("Cannot complete transaction, not enough funds.");
                                }
                            } catch (RemoteException | NotBoundException e) {
                                System.out.println("cannot make transfer between " + NodeName + " and " + processName.get(temp_process_ip));
                            }

                        }
                        System.out.println("after " + op + " " + NodeName + "'s balance is: $" + bal);
                        System.out.print("Current vector clock at " + NodeName + " is:\n");
                        displayClock();

                    }
                    sleep(5000);
                } catch (NumberFormatException | InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    /**
     * This nested class is used to create a new runnable thread to handle
     * Shared Resource update visualization.
     */
    public static class UpdatingSR implements Runnable {

        @Override
        /**
         * This run method visually allows the user to see the amount of time a
         * process spends inside the Critical Section updating the shared
         * resource.
         */
        public void run() {
            while (InCS) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                }
                if (!InCS) {
                    break;
                }
                System.out.println("...");
            }
        }
    }

    /**
     * This nested class is used to create a new runnable thread to keep a tab
     * on timeouts.
     */
    public static class timeout implements Runnable {

        @Override
        /**
         * This run method gracefully releases all the resources held by a
         * process while it is waiting to enter the CS over an extended period
         * of time due to failure of a process to reply back with the CS Access
         * permission. This scenario can arise in case if a process unexpectedly
         * leaves the system before replying to the requesting process after
         * receiving a request or if a large number of processes are in the
         * system and have to wait for an extended period of time to be able to
         * gain access to the Critical Section.
         */
        public void run() {
            try {
                Thread.sleep(timeoutLimit);
            } catch (InterruptedException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }
            if (!RELEASED && !HELD) {
                RELEASED = true;
                WANTED = false;
                HELD = false;
                counter = 0;
                awaitingReply.clear();
                if (!queue.isEmpty()) {
                    System.out.println("Sending remaining requeests now...");
                    for (int i = 0; i < queue.size(); i++) {
                        try {
                            r = LocateRegistry.getRegistry(queue.get(0), port);
                            RIF otherNode = (RIF) r.lookup("process");
                            clock[NodeID - 1]++;
                            otherNode.listen(clock, NodeIP);
                            queue.remove(0);
                        } catch (NotBoundException | AccessException ex) {
                            Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                        } catch (RemoteException ex) {
                            Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                }
                System.out.println("\n**********");
                System.out.println("Timed Out!");
                System.out.println("**********\n");
            }
        }
    }

    /**
     * This nested class is used for creating a runnable thread for the shut
     * down hook, capable of handling process shut down events.
     */
    public static class SDHook extends Thread {

        /**
         * This method allows a process to gracefully leave the system by
         * notifying the bootstrap server of its exit so that it may take the
         * necessary steps to ensure the smooth functioning of the system after
         * this process exits.
         */
        public void run() {
            System.out.println("\n\n********************\nSHUTTING DOWN PROCESS\n********************\n");

            if (NodeIP.equals(bootstrap)) {
                System.out.println("Bootstrap server is now offline.\nAny process alive must be ended to avoid abnormal behaviour.");
            } else if (NodeIP.equals(CSMachine)) {
                System.out.println("CS Machine is now offline.");
            } else {
                System.out.println("End vector clock is: ");
                displayClock();
                System.out.println("End Balance is: " + bal);
                System.out.println("Removing process from other process's maps");
                try {
                    r = LocateRegistry.getRegistry(bootstrap, port);
                    RIF B = (RIF) r.lookup("process");
                    B.removeMe(NodeID);
                } catch (RemoteException | NotBoundException ex) {
                    Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                }
                System.out.println("Removing process from other process's vector clocks");
                try {
                    r = LocateRegistry.getRegistry(bootstrap, port);
                    RIF B = (RIF) r.lookup("process");
                    B.removeMyClock(NodeID);
                } catch (RemoteException | NotBoundException ex) {
                    Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    @Override
    /**
     * This method is called on all the processes alive once a process decides
     * to leave the system. It is called by the bootstrap server in order to
     * notify the processes about the empty ID positions to skip processing them
     * while iterating over the current process ID range.
     */
    public void getEmptyClocks(ArrayList<Integer> empclk) throws RemoteException {
        EmptyClocks = empclk;
    }

    @Override
    /**
     * This method is called on the bootstrap server. This method is called by
     * the exiting process to allow the bootstrap server to reset its state on
     * the vector clocks of all the processes that are currently alive.
     */
    public void removeMyClock(int ID) throws RemoteException {
        EmptyClocks.add(ID);
        Collections.sort(EmptyClocks);

        for (int i = 1; i <= max_processes_shown; i++) {
            if (EmptyClocks.contains(i)) {
                continue;
            }
            try {
                r = LocateRegistry.getRegistry(process.get(i), port);
                RIF pro = (RIF) r.lookup("process");
                pro.rmclock(ID);
                pro.getEmptyClocks(EmptyClocks);
            } catch (RemoteException | NotBoundException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        System.out.println("Processes currently active:");
        if (process.isEmpty()) {
            System.out.println("**None**");
        } else {
            for (int i = 1; i <= max_processes_shown; i++) {
                if (EmptyClocks.contains(i)) {
                    continue;
                }
                System.out.println(processName.get(process.get(i)));
            }
        }
    }

    @Override
    /**
     * This method is used for resetting the vector clock element for the
     * (ID-1)th position. It is called on every alive process by the bootstrap
     * server when the bootstrap server is notified by a process about its exit.
     */
    public void rmclock(int ID) throws RemoteException {
        clock[ID - 1] = 0;
    }

    @Override
    /**
     * called on the bootstrap server by the exiting process, used for correctly
     * updating the process maps of all the processes alive and the maps of the
     * Critical Section machine by removing the exiting process from the maps.
     */
    public void removeMe(int ID) throws RemoteException {
        System.out.println("\nRemoving process: " + processName.get(process.get(ID)));
        String ip = process.get(ID);
        process.remove(ID);
        processName.remove(ip);
        try {
            r = LocateRegistry.getRegistry(CSMachine, port);
            RIF CSM = (RIF) r.lookup("process");
            CSM.rm(ID);
        } catch (RemoteException | NotBoundException ex) {
            Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
        }
        for (int i = 1; i <= max_processes_shown; i++) {
            if (EmptyClocks.contains(i)) {
                continue;
            }
            try {
                r = LocateRegistry.getRegistry(process.get(i), port);
                RIF pro = (RIF) r.lookup("process");
                pro.rm(ID);
            } catch (RemoteException | NotBoundException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

    }

    @Override
    /**
     * This method is called on every process that is alive. It is called by the
     * bootstrap server to remove the exiting process from the maps of the
     * current process.
     */
    public void rm(int ID) throws RemoteException {
        String ip = process.get(ID);
        process.remove(ID);
        processName.remove(ip);
    }

    @Override
    /**
     * This method is called on the bootstrap server. It is called by a new
     * process that wishes to join the system. The bootstrap server assigns this
     * process the next available ID, and notifies the Other alive processes in
     * the system and the CS Machine about the presence of this new process by
     * adding it to their process maps. This method also updates the maximum
     * number of vector clock elements that need to be processes in the system
     * with current number of processes. Does not allow the process to be added
     * to the system if the capacity has been reached.
     */
    public int addme(String IP, String Name) throws RemoteException {
        System.out.println("\nadding new process: " + Name);
        if (process.size() == max_allowed_processes) {
            System.out.println("Cannot add " + Name + ", max limit reached");
            System.out.println("Try again later!");
            return 0;
        }
        int size = process.size();
        int newID = size + 1;
        if (!EmptyClocks.isEmpty()) {
            newID = EmptyClocks.get(0);
            EmptyClocks.remove(0);
        }
        process.put(newID, IP);
        processName.put(IP, Name);
        if (max_processes_shown < process.size()) {
            max_processes_shown = process.size();
        }
        try {
            r = LocateRegistry.getRegistry(CSMachine, port);
            RIF CSM = (RIF) r.lookup("process");
            CSM.updateMap(process);
            CSM.enhanceMap(processName);
        } catch (RemoteException | NotBoundException ex) {
            Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
        }

        try {
            r = LocateRegistry.getRegistry(IP, port);
            RIF newpro = (RIF) r.lookup("process");
            newpro.updateCS(CSMachine);
        } catch (RemoteException | NotBoundException ex) {
            Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
        }

        for (int i = 1; i <= max_processes_shown; i++) {
            if (EmptyClocks.contains(i)) {
                continue;
            }
            try {
                r = LocateRegistry.getRegistry(process.get(i), port);
                RIF pro = (RIF) r.lookup("process");
                pro.updateMap(process);
                pro.enhanceMap(processName);
                pro.getEmptyClocks(EmptyClocks);
                pro.updateDisp(max_processes_shown);
            } catch (RemoteException | NotBoundException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        System.out.println("Processes currently active:");
        if (process.isEmpty()) {
            System.out.println("**None**");
        } else {
            for (int i = 1; i <= max_processes_shown; i++) {
                if (EmptyClocks.contains(i)) {
                    continue;
                }
                System.out.println(processName.get(process.get(i)));
            }
        }

        return newID;
    }

    @Override
    /**
     * This method is called on the processes by the bootstrap server. It is
     * used to update the IP address of the Current CS machine in the System.
     */
    public void updateCS(String CSM) throws RemoteException {
        CSMachine = CSM;
    }

    @Override
    /**
     * This method is called on the processes by the bootstrap server. It is
     * used for notifying all the processes of the maximum number of vector
     * clock elements that need to be processed considering the current strength
     * of the system with respect to the number of processes currently alive.
     */
    public void updateDisp(int maxD) throws RemoteException {
        max_processes_shown = maxD;
    }

    @Override
    /**
     * This method is called on the bootstrap server. It is called by the CS
     * Machine when it wants to be added to the system.
     */
    public void imcsmachine(String node) throws RemoteException {
        System.out.println("adding CS Machine at: " + node);
        CSMachine = node;
    }

    /**
     * This nested class is used for creating a thread that is run on the
     * bootstrap that helps it stay alive.
     */
    public static class bootstraprunner implements Runnable {

        /**
         * Thread simply runs to help the bootstrap server stay alive.
         */
        public void run() {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    /**
     * The main method encompasses the initial few steps required in the setting
     * up of the Bootstrap Server, The Critical Section Machine and any new
     * Process that wishes to join this dynamic environment of the mutual
     * exclusion system.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // register SDHook as shutdown hook
        Runtime.getRuntime().addShutdownHook(new SDHook());

        // variable stores the input command
        String command = args[0];

        // if the current node is a bootstrap server
        if (command.equals("-b")) {
            EmptyClocks = new ArrayList<>();
            try {
                InetAddress IP = InetAddress.getLocalHost();
                NodeName = IP.getHostName();
                NodeIP = IP.getHostAddress();
                bootstrap = NodeIP;
            } catch (UnknownHostException ex) {
                System.out.println("**********************************************");
                System.out.println("Exception while Process Identification in Main");
                System.out.println("**********************************************");
            }
            // Start registry
            try {
                r = LocateRegistry.createRegistry(port);
                r.rebind("process", new RicAg());

            } catch (RemoteException ex) {
                System.out.println("*****************************************");
                System.out.println("Exception while Creating Registry in Main");
                System.out.println("*****************************************");
            }
            System.out.println("bootstrap server has started");
            System.out.println("Public IP for bootstrap is: " + NodeIP);
            Thread bootstrapthread = new Thread(new bootstraprunner());
            bootstrapthread.start();

            // if the current node is a critical section
        } else if (command.equals("-cs")) {
            try {
                ra = new RicAg();
            } catch (RemoteException ex) {
                System.out.println("*******************************************************");
                System.out.println("Exception while instantiating new RicAg object at Main.");
                System.out.println("*******************************************************");
            }

            //ID the process
            try {
                InetAddress IP = InetAddress.getLocalHost();
                NodeName = IP.getHostName();
                NodeIP = IP.getHostAddress();
                CSMachine = NodeIP;
                System.out.println("Name: " + NodeName + " IP: " + NodeIP + " bootstrapIP: " + args[1]);
            } catch (UnknownHostException ex) {
                System.out.println("**********************************************");
                System.out.println("Exception while Process Identification in Main");
                System.out.println("**********************************************");
            }

            // Start registry
            try {
                r = LocateRegistry.createRegistry(port);
                r.rebind("process", new RicAg());

            } catch (RemoteException ex) {
                System.out.println("*****************************************");
                System.out.println("Exception while Creating Registry in Main");
                System.out.println("*****************************************");
            }

            try {
                r = LocateRegistry.getRegistry(args[1], port);
                RIF B = (RIF) r.lookup("process");
                B.imcsmachine(NodeIP);
            } catch (RemoteException | NotBoundException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }

            // if the current node is a new Process
        } else if (!command.equals("-cs") && !command.equals("-b")) {
            bootstrap = command;
            try {
                ra = new RicAg();
            } catch (RemoteException ex) {
                System.out.println("*******************************************************");
                System.out.println("Exception while instantiating new RicAg object at Main.");
                System.out.println("*******************************************************");
            }

            //ID the process
            try {
                InetAddress IP = InetAddress.getLocalHost();
                NodeName = IP.getHostName();
                NodeIP = IP.getHostAddress();
            } catch (UnknownHostException ex) {
                System.out.println("**********************************************");
                System.out.println("Exception while Process Identification in Main");
                System.out.println("**********************************************");
            }

            //initialize clock
            clock = new int[max_allowed_processes];
            for (int i = 0; i < clock.length; i++) {
                clock[i] = 0;
            }
            queue = new ArrayList<>();

            System.out.println("*** Process Identification Completed ***");
            System.out.println("Process Name: " + NodeName + "\nProcess IP: " + NodeIP);

            // Start registry
            try {
                r = LocateRegistry.createRegistry(port);
                r.rebind("process", new RicAg());

            } catch (RemoteException ex) {
                System.out.println("*****************************************");
                System.out.println("Exception while Creating Registry in Main");
                System.out.println("*****************************************");
            }

            //bind to the system
            try {
                r = LocateRegistry.getRegistry(command, port);
                RIF B = (RIF) r.lookup("process");
                NodeID = B.addme(NodeIP, NodeName);
                if (NodeID == 0) {
                    System.out.println("Could not connect. Network already full");
                    System.out.println("ending process...");
                    System.exit(0);
                }
            } catch (RemoteException | NotBoundException ex) {
                Logger.getLogger(RicAg.class.getName()).log(Level.SEVERE, null, ex);
            }

            //if node is not already the bootstrap server or the CS Machine, start scheduler handler and transaction handler threads.
            if (!NodeIP.equals(CSMachine) && !NodeIP.equals(bootstrap)) {
                Thread scheduler;
                Thread trans;
                try {
                    scheduler = new Thread(new RicAg());
                    scheduler.start();
                    trans = new Thread(new Transactions());
                    trans.start();
                } catch (RemoteException ex) {
                    System.out.println("*************************************************************************");
                    System.out.println("Exception while Creating Scheduler and Transaction handler Thread in Main");
                    System.out.println("*************************************************************************");
                }
            }
        }
    }
}
