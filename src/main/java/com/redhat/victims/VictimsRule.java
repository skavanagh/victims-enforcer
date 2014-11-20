package com.redhat.victims;

/*
 * #%L
 * This file is part of victims-enforcer.
 * %%
 * Copyright (C) 2013 The Victims Project
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;

import com.redhat.victims.database.VictimsDB;
import com.redhat.victims.database.VictimsDBInterface;


/**
 * This class is the main entry point for working with the Maven Enforcer
 * plug-in. It provides the logic to synchronize a local database with a remote
 * database of vulnerable Java artifacts. This database is used to check for any
 * dependencies used within the project that may have known vulnerabilities.
 *
 * @author gmurphy
 */
public class VictimsRule implements EnforcerRule {

  /*
   * Configuration options available in pom.xml
   */
  private String metadata = Settings.defaults.get(Settings.METADATA);
  private String fingerprint = Settings.defaults.get(Settings.FINGERPRINT);
  private String updates = Settings.defaults.get(Settings.UPDATE_DATABASE);
  private String baseUrl = null;
  private String entryPoint = null;
  private String jdbcDriver = null;
  private String jdbcUrl = null;
  private String jdbcUser = null;
  private String jdbcPass = null;
  

  /**
   * Main entry point for the enforcer rule.
   *
   * @param helper
   * @throws EnforcerRuleException
   */

  public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {

      HashSet<Artifact> artifacts = new HashSet<Artifact>();
      ArtifactCollector[] collectors = {
              new DependencyTreeCollector(),
              new ReactorCollector(),
              new BaseArtifactCollector()
      };

      for (ArtifactCollector collector : collectors ){
          try {
              artifacts.addAll(collector.with(helper).getArtifacts());
          } catch (Throwable e) {
              // Expect failures for API incompatabilities
              helper.getLog().debug("[victims-enforcer] Artifact Collector failed: " + collector.getClass().getName());
              helper.getLog().debug(e.toString());
          }
      }

      execute(setupContext(helper.getLog()), artifacts);

  }

  /**
   * Configure execution context based on sensible defaults and
   * overrides in the pom.xml configuration.
   * @param log
   * @return Configured execution context
   * @throws EnforcerRuleException
   */
  public ExecutionContext setupContext(Log log) throws EnforcerRuleException {

    ExecutionContext ctx = new ExecutionContext();
    ctx.setLog(log);
    ctx.setSettings(new Settings());
    ctx.getSettings().set(Settings.METADATA, metadata);
    ctx.getSettings().set(Settings.FINGERPRINT, fingerprint);

    // Only need to query using one hashing mechanism
    System.setProperty(VictimsConfig.Key.ALGORITHMS, "SHA512");
   
    if (baseUrl != null){
      System.setProperty(VictimsConfig.Key.URI, baseUrl);
      ctx.getSettings().set(VictimsConfig.Key.URI, baseUrl);
    }
    if (entryPoint != null){
      System.setProperty(VictimsConfig.Key.ENTRY, entryPoint);
      ctx.getSettings().set(VictimsConfig.Key.URI, baseUrl);
    }
    if (jdbcDriver != null){
      System.setProperty(VictimsConfig.Key.DB_DRIVER, jdbcDriver);
      ctx.getSettings().set(VictimsConfig.Key.DB_DRIVER, jdbcDriver);
    }
    if (jdbcUrl != null){
      System.setProperty(VictimsConfig.Key.DB_URL, jdbcUrl);
      ctx.getSettings().set(VictimsConfig.Key.DB_URL, jdbcUrl);
    }
    if (jdbcUser != null){
      System.setProperty(VictimsConfig.Key.DB_USER, jdbcUser);
      ctx.getSettings().set(VictimsConfig.Key.DB_USER, jdbcUser);
    }
    if (jdbcPass != null){
      System.setProperty(VictimsConfig.Key.DB_PASS, jdbcPass);
      ctx.getSettings().set(VictimsConfig.Key.DB_PASS, "(not shown)");
    }
    if(updates != null){
      System.setProperty(VictimsConfig.Key.DB_UPDATE, updates);
      ctx.getSettings().set(VictimsConfig.Key.DB_UPDATE, updates);
    }
    
    // Setup database
    try {
      ctx.setDatabase(VictimsDB.db());
    } catch (VictimsException e) {
      log.debug(e);
      throw new EnforcerRuleException(e.getMessage(), e);
    }

    // Setup cache
    try {
      ctx.setCache(new VictimsResultCache());
    } catch (VictimsException e){
      log.debug(e);
      throw new EnforcerRuleException(e.getMessage(), e);
    }

    // Validate settings
    try {
      ctx.getSettings().validate();
      ctx.getSettings().show(ctx.getLog());
   
    } catch (VictimsException e) {
      log.debug(e);
      throw new EnforcerRuleException(e.getMessage(), e);
    }

    return ctx;
  }

  /**
   * This is a helper method that processes a single result that 
   * has been executed. 
   * 
   * If any exceptions were thrown during execution they are inspected
   * to see if they indicate a vulnerable artifact. The result is also 
   * added to the cache for the current execution context.  
   * 
   * 
   * @param ctx - The execution context the result was generated under
   * @param result - The result to examine
   * @throws VictimsException
   * @throws VulnerableArtifactException
   * @throws EnforcerRuleException
   */
  private void processResult(ExecutionContext ctx, Future<ArtifactStub> result) 
      throws VictimsException, VulnerableArtifactException, EnforcerRuleException  {
    
    VictimsResultCache cache = ctx.getCache();
    Log log = ctx.getLog();
    
    try {
      
      ArtifactStub checked = result.get();
      if (checked != null){
        log.debug("[victims-enforcer] done: " + checked.getId());
        cache.add(checked.getId(), null);
      }
      
    } catch (InterruptedException e){
      log.info(e.getMessage());
    
    } catch (ExecutionException e){
      
      log.debug(e);
      Throwable cause = e.getCause();
      
      if (cause instanceof VulnerableArtifactException){
        
        VulnerableArtifactException ve = (VulnerableArtifactException) cause;
        // cache vulnerable artifact
        cache.add(ve.getId(), ve.getVulnerabilites());
        
        // Log the error and rethrow in case a fatal error
        log.warn(ve.getLogMessage());
        throw ve;

      } else { 
        throw new EnforcerRuleException(e.getCause().getMessage(), e);
      }
    }
  }

  /**
   * Scan the supplied artifacts given the provided execution context. An
   * exception will be raised if a vulnerable artifact has been detected.
   * @param ctx
   * @param artifacts
   * @throws EnforcerRuleException
   */
  public void execute(ExecutionContext ctx, Set<Artifact> artifacts) throws EnforcerRuleException {
    
    VictimsResultCache cache = ctx.getCache();
    Log log = ctx.getLog();
    int cores = Runtime.getRuntime().availableProcessors();
    ExecutorService executor = null;
    ExecutorCompletionService<ArtifactStub> completionService = null;
    List<Future<ArtifactStub>> results = null; 
    
    try {
      
      // Synchronize database with victims service
      try {
        // Disable updates via command line -Dvictims.skip.update=true
        String override = System.getProperty(Settings.UPDATES_OVERRIDE);
        if (override != null && override.equalsIgnoreCase("true")){
          log.warn("[victims-enforcer] Updates disabled via system property.");
        } else{
          ctx.getDatabase().synchronize();
        }
      } catch (VictimsException e){
          log.warn("Unable to update victims database! Your CVE records might be out of date.");
          log.debug(e.toString());
      }
      // Concurrently process each dependency 
      executor = Executors.newFixedThreadPool(cores);
      completionService = new ExecutorCompletionService<ArtifactStub>(executor);
      
      results = new ArrayList<Future<ArtifactStub>>();
      
      for (Artifact a : artifacts){
           
        // Check if we've already inspected this dependency
        if (cache.exists(a.getId())){
          
          HashSet<String> cves = cache.get(a.getId());
          log.debug("[victims-enforcer] cached: " + a.getId());
          if (! cves.isEmpty()){
            
            VulnerableArtifactException err = new VulnerableArtifactException(a, Settings.FINGERPRINT, cves);
            log.warn(err.getLogMessage());
            
            if (err.isFatal(ctx)){
              throw new EnforcerRuleException(err.getErrorMessage(), err);
            }
          }
          continue;
        }
            
        // Not in cache process artifact
        results.add(completionService.submit(new VictimsCommand(ctx, a)));    
        
        // Poll completion service for completed tasks to short circuit
        // on failure conditions.
        Future<ArtifactStub> result = completionService.poll();
        if (result != null){
          try { 
            results.remove(result);
            processResult(ctx, result);
          
          } catch (VulnerableArtifactException e){
           
            if (e.isFatal(ctx)){
              // Cancel other jobs
              for (Future<ArtifactStub> f : results){
                f.cancel(true);
              }
              throw new EnforcerRuleException(e.getErrorMessage(), e);
            }
          }
        }
      
      }
      executor.shutdown();
      
      // Process any remaining results. 
      for (Future<ArtifactStub> future : results){
        processResult(ctx, future); 
      }
      
    } catch (VulnerableArtifactException e){
        // fatal exception
        if (e.isFatal(ctx)){
          throw new EnforcerRuleException(e.getErrorMessage(), e);
        }
      
    } catch (VictimsException e) {
      log.debug(e);
      throw new EnforcerRuleException(e.getMessage(), e);
    
    } finally { 
      
      if (executor != null){
        executor.shutdownNow();
       
      }
    }
  }

  /**
   * The database is always synchronized with the Victims Server. The initial
   * import of entries will take some time but subsequent requests should be
   * relatively fast.
   *
   * @return Always will return false.
   */
  public boolean isCacheable() {
    return false;
  }

  /**
   * Feature not used by this rule.
   *
   * @param er
   * @return Always returns false
   */
  public boolean isResultValid(EnforcerRule er) {
    return false;
  }

  /**
   * Feature not used by this plug-in. Return a bogus value.
   *
   * @return Not used
   */
  public String getCacheId() {
    return " " + new java.util.Date().getTime();
  }
  
  
}
