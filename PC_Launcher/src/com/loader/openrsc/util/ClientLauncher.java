package com.loader.openrsc.util;

import com.loader.openrsc.Constants;
import com.loader.openrsc.Launcher;

import javax.swing.*;
import java.io.File;

public class ClientLauncher {
	private static ClassLoader loader;
	private static Class<?> mainClass;
	private static JFrame frame;

	public static JFrame getFrame() {
		return frame;
	}

	public static void launchClient() throws IllegalArgumentException, SecurityException {
		startProcess();
	}

	public static void launchClient_openpk() throws IllegalArgumentException, SecurityException {
		startProcess_openpk();
	}

	private static void exit() {
		System.exit(0);
	}

	private static void startProcess() {
		try {
			File f = new File(Constants.CONF_DIR + File.separator + Constants.CLIENT_FILENAME);
			ProcessBuilder pb = new ProcessBuilder(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java", "-jar", f.getAbsolutePath());
			pb.start();
		} catch (Exception e) {
			Launcher.getPopup().setMessage("Client failed to launch. Please try again or notify staff.");
			Launcher.getPopup().showFrame();
			e.printStackTrace();
		}
	}

	private static void startProcess_openpk() {
		try {
			File f = new File(Constants.CONF_DIR + File.separator + Constants.OPENPK_FILENAME);
			ProcessBuilder pb = new ProcessBuilder(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java", "-jar", f.getAbsolutePath());
			pb.start();
		} catch (Exception e) {
			Launcher.getPopup().setMessage("Client failed to launch. Please try again or notify staff.");
			Launcher.getPopup().showFrame();
			e.printStackTrace();
		}
	}
}
