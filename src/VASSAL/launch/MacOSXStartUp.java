/*
 * $Id: ModuleManager.java 3512 2008-05-01 20:11:28Z uckelman $
 *
 * Copyright (c) 2008 by Joel Uckelman
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, copies are available
 * at http://www.opensource.org.
 */
package VASSAL.launch;

import java.io.File;
import javax.swing.UIManager;

import com.apple.eawt.Application;
import com.apple.eawt.ApplicationAdapter;
import com.apple.eawt.ApplicationEvent;

import VASSAL.tools.ErrorLog;


public class MacOSXStartUp extends StartUp {
  @Override
  public void initSystemProperties() {
    super.initSystemProperties();
    initMacOSXSpecificProperties();
  }

  protected void initMacOSXSpecificProperties() {
    // use the system menu bar
    System.setProperty("apple.laf.useScreenMenuBar", "true");

    // put "VASSAL" in the system menu bar
    System.setProperty(
      "com.apple.mrj.application.apple.menu.about.name", "VASSAL");

    // show the grow box in the lower right corner of windows
    System.setProperty("apple.awt.showGrowBox", "true");

    // grow box should not overlap other elements
    System.setProperty("com.apple.mrj.application.growbox.intrudes", "true");

    // live resize of app windows
    System.setProperty("com.apple.mrj.application.live-resize", "true");

    // use native LookAndFeel---must be after other Apple properties
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    }
    catch (Exception e) {
      ErrorLog.warn(e);
    }
  }
}