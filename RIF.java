/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
//package ricag;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Remote Interface for Ricart Agrawala Mutual Exclusion system for facilitating RMI. used by 'RicAg.java' file. 
 * @author Aditya Advani
 */
public interface RIF extends Remote {
    public void ask(String Node, int[] otherclock, int id)throws RemoteException;
    public void listen(int[] otherclock, String IP) throws RemoteException;
    public void enterCS(String node) throws RemoteException;
    public void sendAmount(int amt, int[] otherclock, String Node) throws RemoteException;
    public int addme(String IP, String Name) throws RemoteException;
    public void enhanceMap(HashMap<String, String> ep) throws RemoteException;
    public void updateMap(HashMap<Integer, String> p) throws RemoteException;
    public void updateCS(String CSM) throws RemoteException;
    public void imcsmachine(String node) throws RemoteException;
    public void rm(int ID) throws RemoteException;
    public void removeMe(int ID) throws RemoteException;
    public void removeMyClock(int NodeID) throws RemoteException;
    public void rmclock(int ID)throws RemoteException;
    public void getEmptyClocks(ArrayList<Integer> empclk) throws RemoteException;
    public void updateDisp(int maxD) throws RemoteException;
}
