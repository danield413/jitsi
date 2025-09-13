/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.java.sip.communicator.launcher;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import net.java.sip.communicator.launchutils.*;
import org.jitsi.impl.osgi.framework.*;
import org.jitsi.impl.osgi.framework.launch.*;
import org.jitsi.osgi.framework.*;
import org.osgi.framework.*;
import org.osgi.framework.launch.*;
import org.osgi.framework.startlevel.*;
import org.reflections.*;
import org.reflections.util.*;
import org.slf4j.*;
import org.slf4j.bridge.*;

/**
 * Starts Jitsi.
 *
 * @author Yana Stamcheva
 * @author Lyubomir Marinov
 * @author Emil Ivov
 * @author Sebastien Vincent
 */
public class Jitsi
{
    /**
     * Legacy home directory names that we can use if current dir name is the
     * currently active name (overridableDirName).
     */
    private static final String[] LEGACY_DIR_NAMES
        = { ".sip-communicator", "SIP Communicator" };

    /**
     * The name of the property that stores the home dir for cache data, such
     * as avatars and spelling dictionaries.
     */
    public static final String PNAME_SC_CACHE_DIR_LOCATION =
            "net.java.sip.communicator.SC_CACHE_DIR_LOCATION";

    /**
     * The name of the property that stores the home dir for application log
     * files (not history).
     */
    public static final String PNAME_SC_LOG_DIR_LOCATION =
            "net.java.sip.communicator.SC_LOG_DIR_LOCATION";

    /**
     * Name of the possible configuration file names (used under macosx).
     */
    private static final String[] LEGACY_CONFIGURATION_FILE_NAMES
        = {
            "sip-communicator.properties",
            "jitsi.properties"
        };

    /**
     * The currently active name.
     */
    private static final String OVERRIDABLE_DIR_NAME = "Jitsi";

    /**
     * The name of the property that stores our home dir location.
     */
    public static final String PNAME_SC_HOME_DIR_LOCATION
        = "net.java.sip.communicator.SC_HOME_DIR_LOCATION";

    /**
     * The name of the property that stores our home dir name.
     */
    public static final String PNAME_SC_HOME_DIR_NAME
        = "net.java.sip.communicator.SC_HOME_DIR_NAME";

    // REFACTORIZACIÓN 2: Introduce Enum - Nuevo enum para manejar sistemas operativos
    /**
     * Enum that represents different operating systems and their specific configurations.
     * This encapsulates OS-specific logic and eliminates conditional statements throughout the code.
     */
    private enum OperatingSystem {
        MAC("Mac") {
            @Override
            public String getProfileLocation(String userHome) {
                return userHome + File.separator + "Library" + File.separator + "Application Support";
            }
            
            @Override
            public String getCacheLocation(String userHome) {
                return userHome + File.separator + "Library" + File.separator + "Caches";
            }
            
            @Override
            public String getLogLocation(String userHome) {
                return userHome + File.separator + "Library" + File.separator + "Logs";
            }
            
            @Override
            public String getDefaultName() {
                return OVERRIDABLE_DIR_NAME;
            }
        },
        
        WINDOWS("Windows") {
            @Override
            public String getProfileLocation(String userHome) {
                return System.getenv("APPDATA");
            }
            
            @Override
            public String getCacheLocation(String userHome) {
                return System.getenv("LOCALAPPDATA");
            }
            
            @Override
            public String getLogLocation(String userHome) {
                return System.getenv("LOCALAPPDATA");
            }
            
            @Override
            public String getDefaultName() {
                return OVERRIDABLE_DIR_NAME;
            }
        },
        
        OTHER("") {
            @Override
            public String getProfileLocation(String userHome) {
                return userHome;
            }
            
            @Override
            public String getCacheLocation(String userHome) {
                return userHome;
            }
            
            @Override
            public String getLogLocation(String userHome) {
                return userHome;
            }
            
            @Override
            public String getDefaultName() {
                return ".jitsi";
            }
        };

        private final String osNamePattern;

        OperatingSystem(String osNamePattern) {
            this.osNamePattern = osNamePattern;
        }

        public abstract String getProfileLocation(String userHome);
        public abstract String getCacheLocation(String userHome);
        public abstract String getLogLocation(String userHome);
        public abstract String getDefaultName();

        /**
         * Factory method to detect the current operating system.
         * @return the appropriate OperatingSystem enum value
         */
        public static OperatingSystem detect() {
            String osName = System.getProperty("os.name", "unknown");
            for (OperatingSystem os : values()) {
                if (os != OTHER && osName.contains(os.osNamePattern)) {
                    return os;
                }
            }
            return OTHER;
        }
    }

    /**
     * Starts Jitsi.
     *
     * @param args command line args if any
     * @throws Exception whenever it makes sense.
     */
    public static void main(String[] args)
        throws Exception
    {
        try (var cl = new BundleClassLoader(Jitsi.class.getClassLoader()))
        {
            var c = cl.loadClass(Jitsi.class.getName());
            var m = c.getDeclaredMethod("mainWithCl", String[].class);
            m.invoke(null, (Object) args);
        }
    }

    public static void mainWithCl(String[] args)
        throws Exception
    {
        init();
        handleArguments(args);
        var fw = startCustomOsgi();
        fw.waitForStop(0);
    }

    private static Framework startCustomOsgi() throws BundleException
    {
        var options = Map.of(Constants.FRAMEWORK_BEGINNING_STARTLEVEL, "3");
        Framework fw = new FrameworkImpl(options, Jitsi.class.getClassLoader());
        fw.init();
        var bundleContext = fw.getBundleContext();
        var reflections = new Reflections(new ConfigurationBuilder()
            .addClassLoaders(Jitsi.class.getClassLoader())
            .forPackages("org.jitsi", "net.java.sip"));

        for (final var activator : reflections.getSubTypesOf(BundleActivator.class))
        {
            if ((activator.getModifiers() & Modifier.ABSTRACT) == Modifier.ABSTRACT)
            {
                continue;
            }

            var url = activator.getProtectionDomain().getCodeSource().getLocation().toString();
            var bundle = bundleContext.installBundle(url);
            var startLevel = bundle.adapt(BundleStartLevel.class);
            startLevel.setStartLevel(2);
            var bundleActivator = bundle.adapt(BundleActivatorHolder.class);
            bundleActivator.addBundleActivator(activator);
        }

        new SplashScreenUpdater(bundleContext.getBundles().length, bundleContext);
        fw.start();
        return fw;
    }

    private static void init()
    {
        setSystemProperties();
        setScHomeDir();
        Logger logger = LoggerFactory.getLogger(Jitsi.class);
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        logger.info("home={}, cache={}, log={}, dir={}",
            System.getProperty(PNAME_SC_HOME_DIR_LOCATION),
            System.getProperty(PNAME_SC_CACHE_DIR_LOCATION),
            System.getProperty(PNAME_SC_LOG_DIR_LOCATION),
            System.getProperty(PNAME_SC_HOME_DIR_NAME));
    }

    private static void handleArguments(String[] args)
    {
        //first - pass the arguments to our arg handler
        LaunchArgHandler argHandler = LaunchArgHandler.getInstance();
        int argHandlerRes = argHandler.handleArgs(args);

        if ( argHandlerRes == LaunchArgHandler.ACTION_EXIT
            || argHandlerRes == LaunchArgHandler.ACTION_ERROR)
        {
            System.err.println("ArgHandler error: " + argHandler.getErrorCode());
            System.exit(argHandler.getErrorCode());
        }

        //lock our config dir so that we would only have a single instance of
        //sip communicator, no matter how many times we start it (use mainly
        //for handling sip: uris after starting the application)
        if ( argHandlerRes != LaunchArgHandler.ACTION_CONTINUE_LOCK_DISABLED )
        {
            switch (new JitsiLock().tryLock(args))
            {
            case JitsiLock.LOCK_ERROR:
                System.err.println("Failed to lock Jitsi's "
                    +"configuration directory.\n"
                    +"Try launching with the --multiple param.");
                System.exit(JitsiLock.LOCK_ERROR);
                break;
            case JitsiLock.ALREADY_STARTED:
                System.out.println(
                    "Jitsi is already running and will "
                        +"handle your parameters (if any).\n"
                        +"Launch with the --multiple param to override this "
                        +"behaviour.");

                //we exit with success because for the user that's what it is.
                System.exit(JitsiLock.SUCCESS);
                break;
            case JitsiLock.SUCCESS:
                //Successfully locked, continue as normal.
                break;
            }
        }
    }

    /**
     * Sets the system properties net.java.sip.communicator.SC_HOME_DIR_LOCATION
     * and net.java.sip.communicator.SC_HOME_DIR_NAME (if they aren't already
     * set) in accord with the OS conventions specified by the name of the OS.
     * Please leave the access modifier as package (default) to allow launch-
     * wrappers to call it.
     *
     */
    // REFACTORIZACIÓN 1: Extract Method - Método principal simplificado mediante extracción de submétodos
    static void setScHomeDir()
    {
        /*
         * Though we'll be setting the SC_HOME_DIR_* property values depending
         * on the OS running the application, we have to make sure we are
         * compatible with earlier releases i.e. use
         * ${user.home}/.sip-communicator if it exists (and the new path isn't
         * already in use).
         */
        String profileLocation = System.getProperty(PNAME_SC_HOME_DIR_LOCATION);
        String cacheLocation = System.getProperty(PNAME_SC_CACHE_DIR_LOCATION);
        String logLocation = System.getProperty(PNAME_SC_LOG_DIR_LOCATION);
        String name = System.getProperty(PNAME_SC_HOME_DIR_NAME);

        boolean isHomeDirnameForced = name != null;

        if (profileLocation == null
            || cacheLocation == null
            || logLocation == null
            || name == null)
        {
            // REFACTORIZACIÓN 1: Extract Method - Configuración inicial de directorios
            DirectoryConfiguration config = configureDirectories(name);
            
            profileLocation = config.profileLocation;
            cacheLocation = config.cacheLocation;
            logLocation = config.logLocation;
            name = config.name;

            /*
             * As it was noted earlier, make sure we're compatible with previous
             * releases. If the home dir name is forced (set as system property)
             * doesn't look for the default dir.
             */
            if (!isHomeDirnameForced) {
                // REFACTORIZACIÓN 1: Extract Method - Compatibilidad con versiones anteriores
                DirectoryConfiguration legacyConfig = handleLegacyCompatibility(profileLocation, name);
                profileLocation = legacyConfig.profileLocation;
                name = legacyConfig.name;
            }

            // REFACTORIZACIÓN 1: Extract Method - Manejo de nombres de directorios legacy
            DirectoryConfiguration finalConfig = handleLegacyDirectoryNames(profileLocation, name, isHomeDirnameForced);
            profileLocation = finalConfig.profileLocation;
            name = finalConfig.name;

            System.setProperty(PNAME_SC_HOME_DIR_LOCATION, profileLocation);
            System.setProperty(PNAME_SC_CACHE_DIR_LOCATION, cacheLocation);
            System.setProperty(PNAME_SC_LOG_DIR_LOCATION, logLocation);
            System.setProperty(PNAME_SC_HOME_DIR_NAME, name);
        }

        // when we end up with the home dirs, make sure we have log dir
        new File(new File(logLocation, name), "log").mkdirs();
    }

    // REFACTORIZACIÓN 1: Extract Method - Clase auxiliar para encapsular configuración de directorios
    /**
     * Helper class to encapsulate directory configuration data.
     * This improves code readability and makes parameter passing cleaner.
     */
    private static class DirectoryConfiguration {
        String profileLocation;
        String cacheLocation;
        String logLocation;
        String name;
        
        DirectoryConfiguration(String profileLocation, String cacheLocation, String logLocation, String name) {
            this.profileLocation = profileLocation;
            this.cacheLocation = cacheLocation;
            this.logLocation = logLocation;
            this.name = name;
        }
    }

    // REFACTORIZACIÓN 1: Extract Method - Configuración inicial de directorios basada en el SO
    /**
     * Configures directories according to the operating system conventions.
     * Uses the OperatingSystem enum to eliminate OS-specific conditional logic.
     * 
     * @param name the directory name (may be null)
     * @return DirectoryConfiguration with OS-specific paths
     */
    private static DirectoryConfiguration configureDirectories(String name) {
        String defaultLocation = System.getProperty("user.home");
        OperatingSystem os = OperatingSystem.detect(); // Usa el enum para detectar el SO
        String userHome = System.getProperty("user.home");
        
        String profileLocation = os.getProfileLocation(userHome);
        String cacheLocation = os.getCacheLocation(userHome);
        String logLocation = os.getLogLocation(userHome);
        
        if (name == null) {
            name = os.getDefaultName(); // Usa el enum para obtener el nombre por defecto
        }

        // If there are no OS specifics, use the defaults
        if (profileLocation == null)
            profileLocation = defaultLocation;
        if (cacheLocation == null)
            cacheLocation = profileLocation;
        if (logLocation == null)
            logLocation = profileLocation;

        return new DirectoryConfiguration(profileLocation, cacheLocation, logLocation, name);
    }

    // REFACTORIZACIÓN 1: Extract Method - Manejo de compatibilidad con versiones anteriores
    /**
     * Handles compatibility with previous Jitsi releases by checking for
     * existing default directories.
     * 
     * @param profileLocation current profile location
     * @param name current directory name
     * @return DirectoryConfiguration with potentially updated values for legacy compatibility
     */
    private static DirectoryConfiguration handleLegacyCompatibility(String profileLocation, String name) {
        String defaultLocation = System.getProperty("user.home");
        String defaultName = ".jitsi";
        
        if (!new File(profileLocation, name).isDirectory()
            && new File(defaultLocation, defaultName).isDirectory()) {
            profileLocation = defaultLocation;
            name = defaultName;
        }
        
        return new DirectoryConfiguration(profileLocation, null, null, name);
    }

    // REFACTORIZACIÓN 1: Extract Method - Manejo de nombres de directorios legacy
    /**
     * Handles legacy directory names by checking for existing directories
     * with old naming conventions.
     * 
     * @param profileLocation current profile location
     * @param name current directory name
     * @param isHomeDirnameForced whether the home directory name was forced via system property
     * @return DirectoryConfiguration with potentially updated values for legacy directory names
     */
    private static DirectoryConfiguration handleLegacyDirectoryNames(String profileLocation, String name, boolean isHomeDirnameForced) {
        String defaultLocation = System.getProperty("user.home");
        
        // Whether we should check legacy names
        boolean checkLegacyDirNames = (name == null) || name.equals(OVERRIDABLE_DIR_NAME);
        
        // if we need to check legacy names and there is no current home dir already created
        if (checkLegacyDirNames && !checkHomeFolderExist(profileLocation, name)) {
            // now check whether a legacy dir name exists and use it
            for (String dir : LEGACY_DIR_NAMES) {
                // check the platform specific directory
                if (checkHomeFolderExist(profileLocation, dir)) {
                    name = dir;
                    break;
                }

                // now check it and in the default location
                if (checkHomeFolderExist(defaultLocation, dir)) {
                    name = dir;
                    profileLocation = defaultLocation;
                    break;
                }
            }
        }
        
        return new DirectoryConfiguration(profileLocation, null, null, name);
    }

    /**
     * Checks whether home folder exists. Special situation checked under
     * macosx, due to created folder of the new version of the updater we may
     * end up with our settings in 'SIP Communicator' folder and having 'Jitsi'
     * folder created by the updater(its download location). So we check not
     * only the folder exist but whether it contains any of the known
     * configuration files in it.
     *
     * @param parent the parent folder
     * @param name the folder name to check.
     * @return whether folder exists.
     */
    static boolean checkHomeFolderExist(String parent, String name)
    {
        if(System.getProperty("os.name", "unknown").contains("Mac"))
        {
            for (String f : LEGACY_CONFIGURATION_FILE_NAMES)
            {
                if (new File(new File(parent, name), f).exists())
                    return true;
            }

            return false;
        }

        return new File(parent, name).isDirectory();
    }

    /**
     * Sets some system properties specific to the OS that needs to be set at
     * the very beginning of a program (typically for UI related properties,
     * before AWT is launched).
     */
    private static void setSystemProperties()
    {
        // disable Direct 3D pipeline (used for fullscreen) before
        // displaying anything (frame, ...)
        System.setProperty("sun.java2d.d3d", "false");

        // On Mac OS X when switch in fullscreen, all the monitors goes
        // fullscreen (turns black) and only one monitors has images
        // displayed. So disable this behavior because somebody may want
        // to use one monitor to do other stuff while having other ones with
        // fullscreen stuff.
        System.setProperty("apple.awt.fullscreencapturealldisplays", "false");
    }
}