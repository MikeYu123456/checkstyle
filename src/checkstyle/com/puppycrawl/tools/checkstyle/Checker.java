////////////////////////////////////////////////////////////////////////////////
// checkstyle: Checks Java source code for adherence to a set of rules.
// Copyright (C) 2001-2002  Oliver Burn
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
////////////////////////////////////////////////////////////////////////////////
package com.puppycrawl.tools.checkstyle;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

import antlr.RecognitionException;
import antlr.TokenStreamException;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.FileContents;
import com.puppycrawl.tools.checkstyle.api.LocalizedMessage;
import com.puppycrawl.tools.checkstyle.api.LocalizedMessages;
import com.puppycrawl.tools.checkstyle.api.Utils;

/**
 * This class provides the functionality to check a set of files.
 * @author <a href="mailto:oliver@puppycrawl.com">Oliver Burn</a>
 * @author <a href="mailto:stephane.bailliez@wanadoo.fr">Stephane Bailliez</a>
 */
public class Checker
    implements Defn
{

    /** configuration */
    private final GlobalProperties mConfig;

    /** cache file **/
    private final PropertyCacheFile mCache;

    /** vector of listeners */
    private final ArrayList mListeners = new ArrayList();

    /** used to collect messages TODO: delete */
    private final LocalizedMessages mMessages = new LocalizedMessages();

    /** used to walk an AST and notify the checks */
    private final TreeWalker mWalker;

    /**
     * Creates a new <code>Checker</code> instance.
     *
     * @param aConfig the configuration to use
     * @throws CheckstyleException if an error occurs
     */
    public Checker(Configuration aConfig)
        throws CheckstyleException
    {
        this(aConfig.getGlobalProperties(), aConfig.getCheckConfigurations());
    }

    /**
     * Creates a new <code>Checker</code> instance.
     *
     * @param aConfig the configuration to use
     * @param aConfigs the configuation of the checks to use
     * @throws CheckstyleException if an error occurs
     */
    public Checker(GlobalProperties aConfig, CheckConfiguration[] aConfigs)
        throws CheckstyleException
    {
        mConfig = aConfig;
        mCache = new PropertyCacheFile(aConfig);
        LocalizedMessage.setLocale(new Locale(mConfig.getLocaleLanguage(),
                                              mConfig.getLocaleCountry()));
        mWalker = new TreeWalker(mMessages, mConfig.getTabWidth());
        // TODO: improve the error handing
        for (int i = 0; i < aConfigs.length; i++) {
            final CheckConfiguration config = aConfigs[i];
            // IMPORTANT! Need to use the same class loader that created this
            // class. Otherwise can get ClassCastException problems.
            mWalker.registerCheck(
                config.createInstance(this.getClass().getClassLoader()),
                config);
        }
    }

    /** Cleans up the object **/
    public void destroy()
    {
        mCache.destroy();
        mListeners.clear();
    }

    /**
     * Add the listener that will be used to receive events from the audit
     * @param aListener the nosy thing
     */
    public void addListener(AuditListener aListener)
    {
        mListeners.add(aListener);
    }

    /**
     * Processes a set of files.
     * Once this is done, it is highly recommended to call for
     * the destroy method to close and remove the listeners.
     * @param aFiles the list of files to be audited.
     * @return the total number of errors found
     * @see #destroy()
     */
    public int process(File[] aFiles)
    {
        int total = 0;
        fireAuditStarted();
        for (int i = 0; i < aFiles.length; i++) {
            total += process(aFiles[i]);
        }
        fireAuditFinished();
        return total;
    }

    /**
     * Processes a specified file and prints out all errors found.
     * @return the number of errors found
     * @param aFile the file to process
     **/
    private int process(File aFile)
    {
        // check if already checked and passed the file
        final String fileName = aFile.getPath();
        final long timestamp = aFile.lastModified();
        if (mCache.alreadyChecked(fileName, timestamp)) {
            return 0;
        }

        mMessages.reset();
        try {
            fireFileStarted(fileName);
            final String[] lines = Utils.getLines(fileName);
            final FileContents contents = new FileContents(fileName, lines);
            final DetailAST rootAST = TreeWalker.parse(contents);
            mWalker.walk(rootAST, contents, mConfig.getClassLoader());
        }
        catch (FileNotFoundException fnfe) {
            mMessages.add(new LocalizedMessage(0, Defn.CHECKSTYLE_BUNDLE,
                                               "general.fileNotFound", null));
        }
        catch (IOException ioe) {
            mMessages.add(new LocalizedMessage(
                              0, Defn.CHECKSTYLE_BUNDLE,
                              "general.exception",
                              new String[] {ioe.getMessage()}));
        }
        catch (RecognitionException re) {
            mMessages.add(new LocalizedMessage(0, Defn.CHECKSTYLE_BUNDLE,
                                               "general.exception",
                                               new String[] {re.getMessage()}));
        }
        catch (TokenStreamException te) {
            mMessages.add(new LocalizedMessage(0, Defn.CHECKSTYLE_BUNDLE,
                                               "general.exception",
                                               new String[] {te.getMessage()}));
        }

        if (mMessages.size() == 0) {
            mCache.checkedOk(fileName, timestamp);
        }
        else {
            fireErrors(fileName, mMessages.getMessages());
        }

        fireFileFinished(fileName);
        return mMessages.size();
    }

    /**
     * Create a stripped down version of a filename.
     * @param aFileName the original filename
     * @return the filename where an initial prefix of basedir is stripped
     */
    private String getStrippedFileName(final String aFileName)
    {
        final String stripped;
        final String basedir = mConfig.getBasedir();
        if ((basedir == null) || !aFileName.startsWith(basedir)) {
            stripped = aFileName;
        }
        else {
            // making the assumption that there is text after basedir
            final int skipSep = basedir.endsWith(File.separator) ? 0 : 1;
            stripped = aFileName.substring(basedir.length() + skipSep);
        }
        return stripped;
    }


    /** notify all listeners about the audit start */
    protected void fireAuditStarted()
    {
        final AuditEvent evt = new AuditEvent(this);
        final Iterator it = mListeners.iterator();
        while (it.hasNext()) {
            final AuditListener listener = (AuditListener) it.next();
            listener.auditStarted(evt);
        }
    }

    /** notify all listeners about the audit end */
    protected void fireAuditFinished()
    {
        final AuditEvent evt = new AuditEvent(this);
        final Iterator it = mListeners.iterator();
        while (it.hasNext()) {
            final AuditListener listener = (AuditListener) it.next();
            listener.auditFinished(evt);
        }
    }

    /**
     * notify all listeners about the beginning of a file audit
     * @param aFileName the file to be audited
     */
    protected void fireFileStarted(String aFileName)
    {
        final String stripped = getStrippedFileName(aFileName);
        final AuditEvent evt = new AuditEvent(this, stripped);
        final Iterator it = mListeners.iterator();
        while (it.hasNext()) {
            final AuditListener listener = (AuditListener) it.next();
            listener.fileStarted(evt);
        }
    }

    /**
     * notify all listeners about the end of a file audit
     * @param aFileName the audited file
     */
    protected void fireFileFinished(String aFileName)
    {
        final String stripped = getStrippedFileName(aFileName);
        final AuditEvent evt = new AuditEvent(this, stripped);
        final Iterator it = mListeners.iterator();
        while (it.hasNext()) {
            final AuditListener listener = (AuditListener) it.next();
            listener.fileFinished(evt);
        }
    }

    /**
     * notify all listeners about the errors in a file.
     * @param aFileName the audited file
     * @param aErrors the audit errors from the file
     */
    protected void fireErrors(String aFileName, LocalizedMessage[] aErrors)
    {
        final String stripped = getStrippedFileName(aFileName);
        for (int i = 0; i < aErrors.length; i++) {
            final AuditEvent evt = new AuditEvent(this, stripped, aErrors[i]);
            final Iterator it = mListeners.iterator();
            while (it.hasNext()) {
                final AuditListener listener = (AuditListener) it.next();
                listener.addError(evt);
            }
        }
    }
}
