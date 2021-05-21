package net.yudichev.googlephotosupload.cli;

import net.yudichev.googlephotosupload.core.DependenciesModule;
import net.yudichev.googlephotosupload.core.ResourceBundleModule;
import net.yudichev.googlephotosupload.core.SettingsModule;
import net.yudichev.googlephotosupload.core.UploadPhotosModule;
import net.yudichev.jiotty.common.app.Application;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

import static net.yudichev.googlephotosupload.core.BuildVersion.buildVersion;
import static net.yudichev.googlephotosupload.core.SingleInstanceCheck.otherInstanceRunning;

public final class CliMain {
    private static final Logger logger = LoggerFactory.getLogger(CliMain.class);

    public static void main(String[] args) {
        CommandLineParser parser = new DefaultParser();
        try {
            var commandLine = parser.parse(CliOptions.OPTIONS, args);
            var helpRequested = commandLine.hasOption('h');
            if (helpRequested) {
                printHelp();
            }
            var versionRequested = commandLine.hasOption('v');
            if (versionRequested) {
                logger.info("Version {}", buildVersion());
            }
            if (commandLine.hasOption('r')) {
                var settingsModule = new SettingsModule();
                if (otherInstanceRunning(settingsModule.getSettingsRootPath())) {
                    logger.error("Another copy of the app is already running");
                } else {
                    startApp(settingsModule, commandLine);
                }
            } else if (!helpRequested && !versionRequested) {
                logger.error("Missing option -r");
                printHelp();
            }
        } catch (ParseException e) {
            logger.error(e.getMessage());
            printHelp();
        }
        LogManager.shutdown();
    }

    private static void startApp(SettingsModule settingsModule, CommandLine commandLine) {
        Application.builder()
                .addModule(() -> settingsModule)
                .addModule(() -> DependenciesModule.builder()
                        .setAppSettingsRootDir(settingsModule.getSettingsRootPath())
                        .build())
                .addModule(UploadPhotosModule::new)
                .addModule(ResourceBundleModule::new)
                .addModule(() -> new CliModule(commandLine))
                .build()
                .run();
    }

    private static void printHelp() {
        var helpFormatter = new HelpFormatter();
        helpFormatter.setWidth(100);
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        helpFormatter.printHelp(pw, helpFormatter.getWidth(),
                "Jiotty Photos Uploader",
                null,
                CliOptions.OPTIONS,
                helpFormatter.getLeftPadding(),
                helpFormatter.getDescPadding(),
                null,
                false);
        pw.flush();
        var help = sw.toString();
        logger.info(help);
    }
}
