/* Communication module for Android terminals.  -*- c-file-style: "GNU" -*-

Copyright (C) 2023 Free Software Foundation, Inc.

This file is part of GNU Emacs.

GNU Emacs is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or (at
your option) any later version.

GNU Emacs is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GNU Emacs.  If not, see <https://www.gnu.org/licenses/>.  */

package org.gnu.emacs;

/* This class makes the Emacs server work reasonably on Android.

   There is no way to make the Unix socket publicly available on
   Android.

   Instead, this activity tries to connect to the Emacs server, to
   make it open files the system asks Emacs to open, and to emulate
   some reasonable behavior when Emacs has not yet started.

   First, Emacs registers itself as an application that can open text
   and image files.

   Then, when the user is asked to open a file and selects ``Emacs''
   as the application that will open the file, the system pops up a
   window, this activity, and calls the `onCreate' function.

   `onCreate' then tries very to find the file name of the file that
   was selected, and give it to emacsclient.

   If emacsclient successfully opens the file, then this activity
   starts EmacsActivity (to bring it on to the screen); otherwise, it
   displays the output of emacsclient or any error message that occurs
   and exits.  */

import android.app.AlertDialog;
import android.app.Activity;

import android.content.Context;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;

import android.net.Uri;

import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

public final class EmacsOpenActivity extends Activity
  implements DialogInterface.OnClickListener
{
  private static final String TAG = "EmacsOpenActivity";

  private class EmacsClientThread extends Thread
  {
    private ProcessBuilder builder;

    public
    EmacsClientThread (ProcessBuilder processBuilder)
    {
      builder = processBuilder;
    }

    @Override
    public void
    run ()
    {
      Process process;
      InputStream error;
      String errorText;

      try
	{
	  /* Start emacsclient.  */
	  process = builder.start ();
	  process.waitFor ();

	  /* Now figure out whether or not starting the process was
	     successful.  */
	  if (process.exitValue () == 0)
	    finishSuccess ();
	  else
	    finishFailure ("Error opening file", null);
	}
      catch (IOException exception)
	{
	  finishFailure ("Internal error", exception.toString ());
	}
      catch (InterruptedException exception)
	{
	  finishFailure ("Internal error", exception.toString ());
	}
    }
  }

  @Override
  public void
  onClick (DialogInterface dialog, int which)
  {
    finish ();
  }

  public String
  readEmacsClientLog ()
  {
    File file, cache;
    FileReader reader;
    char[] buffer;
    int rc;
    String what;

    /* Because the ProcessBuilder functions necessary to redirect
       process output are not implemented on Android 7 and earlier,
       print a generic error message.  */

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
      return ("This is likely because the Emacs server"
	      + " is not running, or because you did"
	      + " not grant Emacs permission to access"
	      + " external storage.");

    cache = getCacheDir ();
    file = new File (cache, "emacsclient.log");
    what = "";

    try
      {
	reader = new FileReader (file);
	buffer = new char[2048];

	while ((rc = reader.read (buffer, 0, 2048)) != -1)
	  what += String.valueOf (buffer, 0, 2048);

	reader.close ();
	return what;
      }
    catch (IOException exception)
      {
	return ("Couldn't read emacsclient.log: "
		+ exception.toString ());
      }
  }

  private void
  displayFailureDialog (String title, String text)
  {
    AlertDialog.Builder builder;
    AlertDialog dialog;

    builder = new AlertDialog.Builder (this);
    dialog = builder.create ();
    dialog.setTitle (title);

    if (text == null)
      /* Read in emacsclient.log instead.  */
      text = readEmacsClientLog ();

    dialog.setMessage (text);
    dialog.setButton (DialogInterface.BUTTON_POSITIVE, "OK", this);
    dialog.show ();
  }

  /* Check that the specified FILE is readable.  If Android 4.4 or
     later is being used, return URI formatted into a `/content/' file
     name.

     If it is not, then copy the file in FD to a location in the
     system cache directory and return the name of that file.  */

  private String
  checkReadableOrCopy (String file, ParcelFileDescriptor fd,
		       Uri uri)
    throws IOException, FileNotFoundException
  {
    File inFile;
    FileOutputStream outStream;
    InputStream stream;
    byte buffer[];
    int read;
    String content;

    Log.d (TAG, "checkReadableOrCopy: " + file);

    inFile = new File (file);

    if (inFile.canRead ())
      return file;

    Log.d (TAG, "checkReadableOrCopy: NO");

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
      {
	content = "/content/" + uri.getEncodedAuthority ();

	for (String segment : uri.getPathSegments ())
	  content += "/" + Uri.encode (segment);

	/* Append the URI query.  */

	if (uri.getEncodedQuery () != null)
	  content += "?" + uri.getEncodedQuery ();

	Log.d (TAG, "checkReadableOrCopy: " + content);

	return content;
      }

    /* inFile is now the file being written to.  */
    inFile = new File (getCacheDir (), inFile.getName ());
    buffer = new byte[4098];
    outStream = new FileOutputStream (inFile);
    stream = new FileInputStream (fd.getFileDescriptor ());

    try
      {
	while ((read = stream.read (buffer)) >= 0)
	  outStream.write (buffer, 0, read);
      }
    finally
      {
	/* Note that this does not close FD.

	   Keep in mind that execution is transferred to ``finally''
	   even if an exception happens inside the while loop
	   above.  */
	stream.close ();
	outStream.close ();
      }

    return inFile.getCanonicalPath ();
  }

  /* Finish this activity in response to emacsclient having
     successfully opened a file.

     In the main thread, close this window, and open a window
     belonging to an Emacs frame.  */

  public void
  finishSuccess ()
  {
    runOnUiThread (new Runnable () {
	@Override
	public void
	run ()
	{
	  Intent intent;

	  intent = new Intent (EmacsOpenActivity.this,
			       EmacsActivity.class);

	  /* This means only an existing frame will be displayed.  */
	  intent.addFlags (Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
	  startActivity (intent);

	  EmacsOpenActivity.this.finish ();
	}
      });
  }

  /* Finish this activity after displaying a dialog associated with
     failure to open a file.

     Use TITLE as the title of the dialog.  If TEXT is non-NULL,
     display that text in the dialog.  Otherwise, use the contents of
     emacsclient.log in the cache directory instead, or describe why
     that file cannot be read.  */

  public void
  finishFailure (final String title, final String text)
  {
    runOnUiThread (new Runnable () {
	@Override
	public void
	run ()
	{
	  displayFailureDialog (title, text);
	}
      });
  }

  public String
  getLibraryDirectory ()
  {
    int apiLevel;
    Context context;

    context = getApplicationContext ();
    apiLevel = Build.VERSION.SDK_INT;

    if (apiLevel >= Build.VERSION_CODES.GINGERBREAD)
      return context.getApplicationInfo().nativeLibraryDir;
    else if (apiLevel >= Build.VERSION_CODES.DONUT)
      return context.getApplicationInfo().dataDir + "/lib";

    return "/data/data/" + context.getPackageName() + "/lib";
  }

  public void
  startEmacsClient (String fileName)
  {
    String libDir;
    ProcessBuilder builder;
    Process process;
    EmacsClientThread thread;
    File file;

    libDir = getLibraryDirectory ();
    builder = new ProcessBuilder (libDir + "/libemacsclient.so",
				  fileName, "--reuse-frame",
				  "--timeout=10", "--no-wait");

    /* Redirection is unfortunately not possible in Android 7 and
       earlier.  */

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      {
	file = new File (getCacheDir (), "emacsclient.log");

	/* Redirect standard error to a file so that errors can be
	   meaningfully reported.  */

	if (file.exists ())
	  file.delete ();

	builder.redirectError (file);
      }

    /* Track process output in a new thread, since this is the UI
       thread and doing so here can cause deadlocks when EmacsService
       decides to wait for something.  */

    thread = new EmacsClientThread (builder);
    thread.start ();
  }

  @Override
  public void
  onCreate (Bundle savedInstanceState)
  {
    String action, fileName;
    Intent intent;
    Uri uri;
    ContentResolver resolver;
    ParcelFileDescriptor fd;
    byte[] names;
    String errorBlurb;

    super.onCreate (savedInstanceState);

    /* Obtain the intent that started Emacs.  */
    intent = getIntent ();
    action = intent.getAction ();

    if (action == null)
      {
	finish ();
	return;
      }

    /* Now see if the action specified is supported by Emacs.  */

    if (action.equals ("android.intent.action.VIEW")
	|| action.equals ("android.intent.action.EDIT")
	|| action.equals ("android.intent.action.PICK"))
      {
	/* Obtain the URI of the action.  */
	uri = intent.getData ();

	if (uri == null)
	  {
	    finish ();
	    return;
	  }

	/* Now, try to get the file name.  */

	if (uri.getScheme ().equals ("file"))
	  fileName = uri.getPath ();
	else
	  {
	    fileName = null;

	    if (uri.getScheme ().equals ("content"))
	      {
		/* This is one of the annoying Android ``content''
		   URIs.  Most of the time, there is actually an
		   underlying file, but it cannot be found without
		   opening the file and doing readlink on its file
		   descriptor in /proc/self/fd.  */
		resolver = getContentResolver ();
		fd = null;

		try
		  {
		    fd = resolver.openFileDescriptor (uri, "r");
		    names = EmacsNative.getProcName (fd.getFd ());

		    /* What is the right encoding here? */

		    if (names != null)
		      fileName = new String (names, "UTF-8");

		    fileName = checkReadableOrCopy (fileName, fd, uri);
		  }
		catch (FileNotFoundException exception)
		  {
		    /* Do nothing.  */
		  }
		catch (IOException exception)
		  {
		    /* Do nothing.  */
		  }

		if (fd != null)
		  {
		    try
		      {
			fd.close ();
		      }
		    catch (IOException exception)
		      {
			/* Do nothing.  */
		      }
		  }
	      }

	    if (fileName == null)
	      {
		errorBlurb = ("The URI: " + uri + " could not be opened"
			      + ", as it does not encode file name inform"
			      + "ation.");
		displayFailureDialog ("Error opening file", errorBlurb);
		return;
	      }
	  }

	/* And start emacsclient.  */
	startEmacsClient (fileName);
      }
    else
      finish ();
  }
}
