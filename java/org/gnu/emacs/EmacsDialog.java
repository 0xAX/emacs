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

import java.util.List;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Context;
import android.util.Log;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;

import android.view.View;
import android.view.ViewGroup;

/* Toolkit dialog implementation.  This object is built from JNI and
   describes a single alert dialog.  Then, `inflate' turns it into
   AlertDialog.  */

public final class EmacsDialog implements DialogInterface.OnDismissListener
{
  private static final String TAG = "EmacsDialog";

  /* List of buttons in this dialog.  */
  private List<EmacsButton> buttons;

  /* Dialog title.  */
  private String title;

  /* Dialog text.  */
  private String text;

  /* Whether or not a selection has already been made.  */
  private boolean wasButtonClicked;

  /* Dialog to dismiss after click.  */
  private AlertDialog dismissDialog;

  private class EmacsButton implements View.OnClickListener,
			    DialogInterface.OnClickListener
  {
    /* Name of this button.  */
    public String name;

    /* ID of this button.  */
    public int id;

    /* Whether or not the button is enabled.  */
    public boolean enabled;

    @Override
    public void
    onClick (View view)
    {
      Log.d (TAG, "onClicked " + this);

      wasButtonClicked = true;
      EmacsNative.sendContextMenu ((short) 0, id);
      dismissDialog.dismiss ();
    }

    @Override
    public void
    onClick (DialogInterface dialog, int which)
    {
      Log.d (TAG, "onClicked " + this);

      wasButtonClicked = true;
      EmacsNative.sendContextMenu ((short) 0, id);
    }
  };

  /* Create a popup dialog with the title TITLE and the text TEXT.
     TITLE may be NULL.  */

  public static EmacsDialog
  createDialog (String title, String text)
  {
    EmacsDialog dialog;

    dialog = new EmacsDialog ();
    dialog.buttons = new ArrayList<EmacsButton> ();
    dialog.title = title;
    dialog.text = text;

    return dialog;
  }

  /* Add a button named NAME, with the identifier ID.  If DISABLE,
     disable the button.  */

  public void
  addButton (String name, int id, boolean disable)
  {
    EmacsButton button;

    button = new EmacsButton ();
    button.name = name;
    button.id = id;
    button.enabled = !disable;
    buttons.add (button);
  }

  /* Turn this dialog into an AlertDialog for the specified
     CONTEXT.

     Upon a button being selected, the dialog will send an
     ANDROID_CONTEXT_MENU event with the id of that button.

     Upon the dialog being dismissed, an ANDROID_CONTEXT_MENU event
     will be sent with an id of 0.  */

  public AlertDialog
  toAlertDialog (Context context)
  {
    AlertDialog dialog;
    int size;
    EmacsButton button;
    LinearLayout layout;
    Button buttonView;
    ViewGroup.LayoutParams layoutParams;

    size = buttons.size ();

    if (size <= 3)
      {
	dialog = new AlertDialog.Builder (context).create ();
	dialog.setMessage (text);
	dialog.setCancelable (true);
	dialog.setOnDismissListener (this);

	if (title != null)
	  dialog.setTitle (title);

	/* There are less than 4 buttons.  Add the buttons the way
	   Android intends them to be added.  */

	if (size >= 1)
	  {
	    button = buttons.get (0);
	    dialog.setButton (DialogInterface.BUTTON_POSITIVE,
			      button.name, button);
	  }

	if (size >= 2)
	  {
	    button = buttons.get (1);
	    dialog.setButton (DialogInterface.BUTTON_NEGATIVE,
			      button.name, button);
	  }

	if (size >= 3)
	  {
	    button = buttons.get (2);
	    dialog.setButton (DialogInterface.BUTTON_NEUTRAL,
			      button.name, button);
	  }
      }
    else
      {
	/* There are more than 4 buttons.  Add them all to a
	   LinearLayout.  */
	layout = new LinearLayout (context);
	layoutParams
	  = new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT,
					   ViewGroup.LayoutParams.WRAP_CONTENT);

	for (EmacsButton emacsButton : buttons)
	  {
	    buttonView = new Button (context);
	    buttonView.setText (emacsButton.name);
	    buttonView.setOnClickListener (emacsButton);
	    buttonView.setLayoutParams (layoutParams);
	    buttonView.setEnabled (emacsButton.enabled);
	    layout.addView (buttonView);
	  }

	layoutParams
	  = new FrameLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT,
					  ViewGroup.LayoutParams.WRAP_CONTENT);
	layout.setLayoutParams (layoutParams);

	/* Add that layout to the dialog's custom view.

	   android.R.id.custom is documented to work.  But looking it
	   up returns NULL, so setView must be used instead.  */

	dialog = new AlertDialog.Builder (context).setView (layout).create ();
	dialog.setMessage (text);
	dialog.setCancelable (true);
	dialog.setOnDismissListener (this);

	if (title != null)
	  dialog.setTitle (title);
      }

    return dialog;
  }

  /* Internal helper for display run on the main thread.  */

  private boolean
  display1 ()
  {
    EmacsActivity activity;
    int size;
    Button buttonView;
    EmacsButton button;
    AlertDialog dialog;

    if (EmacsActivity.focusedActivities.isEmpty ())
      {
	/* If focusedActivities is empty then this dialog may have
	   been displayed immediately after a popup dialog is
	   dismissed.  */

	activity = EmacsActivity.lastFocusedActivity;

	if (activity == null)
	  return false;
      }
    else
      activity = EmacsActivity.focusedActivities.get (0);

    dialog = dismissDialog = toAlertDialog (activity);

    try
      {
	dismissDialog.show ();
      }
    catch (Exception exception)
      {
	/* This can happen when the system decides Emacs is not in the
	   foreground any longer.  */
	return false;
      }

    /* If there are less than four buttons, then they must be
       individually enabled or disabled after the dialog is
       displayed.  */
    size = buttons.size ();

    if (size <= 3)
      {
	if (size >= 1)
	  {
	    button = buttons.get (0);
	    buttonView
	      = dialog.getButton (DialogInterface.BUTTON_POSITIVE);
	    buttonView.setEnabled (button.enabled);
	  }

	if (size >= 2)
	  {
	    button = buttons.get (1);
	    buttonView
	      = dialog.getButton (DialogInterface.BUTTON_NEGATIVE);
	    buttonView.setEnabled (button.enabled);
	  }

	if (size >= 3)
	  {
	    button = buttons.get (2);
	    buttonView
	      = dialog.getButton (DialogInterface.BUTTON_NEUTRAL);
	    buttonView.setEnabled (button.enabled);
	  }
      }

    return true;
  }

  /* Display this dialog for a suitable activity.
     Value is false if the dialog could not be displayed,
     and true otherwise.  */

  public boolean
  display ()
  {
    Runnable runnable;
    final Holder<Boolean> rc;

    rc = new Holder<Boolean> ();
    runnable = new Runnable () {
	@Override
	public void
	run ()
	{
	  synchronized (this)
	    {
	      rc.thing = display1 ();
	      notify ();
	    }
	}
      };

    EmacsService.syncRunnable (runnable);
    return rc.thing;
  }



  @Override
  public void
  onDismiss (DialogInterface dialog)
  {
    Log.d (TAG, "onDismiss: " + this);

    if (wasButtonClicked)
      return;

    EmacsNative.sendContextMenu ((short) 0, 0);
  }
};
