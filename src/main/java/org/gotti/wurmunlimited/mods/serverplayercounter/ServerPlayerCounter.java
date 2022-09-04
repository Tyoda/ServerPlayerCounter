package org.gotti.wurmunlimited.mods.serverplayercounter;

import com.wurmonline.server.Players;
import com.wurmonline.server.ServerEntry;
import com.wurmonline.server.Servers;
import com.wurmonline.server.players.Player;
import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.Versioned;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class ServerPlayerCounter implements WurmServerMod, Configurable, PreInitable, Versioned {
   private static final Logger logger = Logger.getLogger(ServerPlayerCounter.class.getName());
   public static final String version = "ty1.0";
   /**
    * Whether to print Debug messages
    */
   private boolean bDebug = false;
   /**
    * Interval between refreshes (millis)
    */
   private long updateInterval = 5000;

   public void configure(Properties properties) {
      bDebug = Boolean.parseBoolean(properties.getProperty("debug", Boolean.toString(bDebug)));
      Debug("Debugging messages are enabled.");

      updateInterval = Long.parseLong((properties.getProperty("updateInterval", Long.toString(updateInterval))));
      Debug("Update Interval is set to: "+updateInterval+"ms");

      try {
         String logsPath = Paths.get("mods", "logs").toString();
         File newDirectory = new File(logsPath);
         if (!newDirectory.exists()) {
            if(!newDirectory.mkdirs()){
               throw new IOException("Unable to create logs directory.");
            }
         }

         FileHandler fh = new FileHandler(Paths.get(logsPath, ServerPlayerCounter.class.getSimpleName() + ".log").toString(), 1024 * 1024, 200, true);

         if (this.bDebug) {
            fh.setLevel(Level.INFO);
         } else {
            fh.setLevel(Level.WARNING);
         }

         fh.setFormatter(new SimpleFormatter());
         logger.addHandler(fh);
      } catch (IOException e) {
         logger.log(Level.SEVERE, "Unable to add file handler to logger. " + e.getMessage(), e);
      }
   }

   private void Debug(String x) {
      if (bDebug)
         logger.info(x);
   }

   public void preInit() {
      try {
         ClassPool classPool = HookManager.getInstance().getClassPool();
         CtClass ctSteamHandler = classPool.get("com.wurmonline.server.steam.SteamHandler");

         Debug("Adding new field currentPlayers");
         CtField ctCurrentPlayersField = new CtField(CtClass.intType, "currentPlayers", ctSteamHandler);
         ctSteamHandler.addField(ctCurrentPlayersField);

         Debug("Adding new method setCurrentPlayers()");
         CtMethod ctSetCurrentPlayers = CtNewMethod.make("public void setCurrentPlayers(int players){ currentPlayers = players; }", ctSteamHandler);
         ctSteamHandler.addMethod(ctSetCurrentPlayers);

         Debug("Adding method call for SetBotCount()");
         CtMethod ctUpdate = ctSteamHandler.getDeclaredMethod("update");
         ctUpdate.insertAt(0, "steamServerApi.SetBotCount(currentPlayers);");

         Debug("Adding field lastUpdatedPlayerCount");
         CtClass ctServer = classPool.get("com.wurmonline.server.Server");
         CtField ctLastUpdatedPlayerCount = CtField.make("private long lastUpdatedPlayerCount = 0L;", ctServer);
         ctServer.addField(ctLastUpdatedPlayerCount);

         Debug("Adding method call for getPlayersOnline()");
         CtMethod ctServerRun = ctServer.getDeclaredMethod("run");
         ctServerRun.instrument(new ExprEditor() {
            public void edit(MethodCall m) throws CannotCompileException {
               if (m.getClassName().equals("com.wurmonline.server.steam.SteamHandler") && m.getMethodName().equals("update")) {
                  m.replace(""+
                          "if(System.currentTimeMillis() > lastUpdatedPlayerCount + "+updateInterval+") {" +
                                 "steamHandler.setCurrentPlayers(org.gotti.wurmunlimited.mods.serverplayercounter.ServerPlayerCounter.getPlayersOnline());" +
                                 "lastUpdatedPlayerCount = System.currentTimeMillis();" +
                          "}" +
                          "$proceed($$);"
                  );
               }
            }
         });
      } catch (NotFoundException | CannotCompileException e) {
         Debug("Failed while injecting code.");
         throw new HookException(e);
      }
   }

   @SuppressWarnings("unused")
   public static int getPlayersOnline(){
      int currentPlayers = 0;
      for (ServerEntry server : Servers.getAllServers()) {
         if (server.id != com.wurmonline.server.Servers.localServer.id)
            currentPlayers += server.currentPlayers;
      }

      Player[] players = Players.getInstance().getPlayers();

      HashSet<Long> ids = new HashSet<>((int) (players.length * 1.25) + 1);

      for (Player player : players) {
         ids.add(player.getSaveFile().getSteamId().getSteamID64());
      }
      currentPlayers += ids.size();

      return currentPlayers;
   }

   @Override
   public String getVersion(){
      return version;
   }
}
