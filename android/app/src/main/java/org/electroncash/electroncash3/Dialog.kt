package org.electroncash.electroncash3

import android.app.Dialog
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProviders
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.Toast
import com.chaquo.python.PyException
import kotlinx.android.synthetic.main.password.*
import kotlin.properties.Delegates.notNull


abstract class AlertDialogFragment : DialogFragment() {
    class Model : ViewModel() {
        var started = false
    }
    private val model by lazy { ViewModelProviders.of(this).get(Model::class.java) }

    var started = false

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val builder = AlertDialog.Builder(context!!)
        onBuildDialog(builder)
        return builder.create()
    }

    open fun onBuildDialog(builder: AlertDialog.Builder) {}

    // We used to trigger onShowDialog from Dialog.setOnShowListener, but we had crash reports
    // indicating that the fragment context was sometimes null in that listener (#1046, #1108).
    // So use one of the fragment lifecycle methods instead.
    override fun onStart() {
        super.onStart()
        if (!started) {
            started = true
            onShowDialog(dialog as AlertDialog)
        }
        if (!model.started) {
            model.started = true
            onFirstShowDialog(dialog as AlertDialog)
        }
    }

    /** Can be used to do things like configure custom views, or attach listeners to buttons so
     *  they don't always close the dialog. */
    open fun onShowDialog(dialog: AlertDialog) {}

    /** Unlike onShowDialog, this will only be called once, even if the dialog is recreated
     * after a rotation. This can be used to do things like setting the initial state of
     * editable views. */
    open fun onFirstShowDialog(dialog: AlertDialog) {}

    // TODO override onCreateView so we don't have to find views via the dialog.
    override fun getDialog(): Dialog {
        return super.getDialog()!!
    }
}


class MessageDialog() : AlertDialogFragment() {
    constructor(title: String, message: String) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("message", message)
        }
    }
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setTitle(arguments!!.getString("title"))
            .setMessage(arguments!!.getString("message"))
            .setPositiveButton(android.R.string.ok, null)
    }
}


abstract class MenuDialog : AlertDialogFragment() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        val menu = PopupMenu(app, null).menu
        onBuildDialog(builder, menu)

        val items = ArrayList<CharSequence>()
        var checkedItem: Int? = null
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            items.add(item.title)
            if (item.isChecked) {
                if (checkedItem != null) {
                    throw IllegalArgumentException("Menu has multiple checked items")
                }
                checkedItem = i
            }
        }

        val listener = DialogInterface.OnClickListener { _, index ->
            onMenuItemSelected(menu.getItem(index))
        }
        if (checkedItem == null) {
            builder.setItems(items.toTypedArray(), listener)
        } else {
            builder.setSingleChoiceItems(items.toTypedArray(), checkedItem, listener)
        }
    }

    abstract fun onBuildDialog(builder: AlertDialog.Builder, menu: Menu)
    abstract fun onMenuItemSelected(item: MenuItem)
}


abstract class TaskDialog<Result> : DialogFragment() {
    class Model : ViewModel() {
        var state = Thread.State.NEW
        val result = MutableLiveData<Any?>()
        val exception = MutableLiveData<ToastException>()
    }
    private val model by lazy { ViewModelProviders.of(this).get(Model::class.java) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        model.result.observe(this, Observer {
            onFinished {
                @Suppress("UNCHECKED_CAST")
                onPostExecute(it as Result)
            }
        })
        model.exception.observe(this, Observer {
            onFinished { it!!.show() }
        })

        isCancelable = false
        @Suppress("DEPRECATION")
        return android.app.ProgressDialog(this.context).apply {
            setMessage(getString(R.string.please_wait))
        }
    }

    override fun onStart() {
        super.onStart()
        if (model.state == Thread.State.NEW) {
            try {
                model.state = Thread.State.RUNNABLE
                onPreExecute()
                Thread {
                    try {
                        model.result.postValue(doInBackground())
                    } catch (e: ToastException) {
                        model.exception.postValue(e)
                    }
                }.start()
            } catch (e: ToastException) {
                model.exception.postValue(e)
            }
        }
    }

    private fun onFinished(body: () -> Unit) {
        if (model.state == Thread.State.RUNNABLE) {
            model.state = Thread.State.TERMINATED
            body()
            dismiss()
        }
    }

    /** This method is called on the UI thread. doInBackground will be called on the same
     * fragment instance after it returns. If this method throws a ToastException, it will be
     * displayed, and doInBackground will not be called. */
    open fun onPreExecute() {}

    /** This method is called on a background thread. It should not access user interface
     * objects in any way, as they may be destroyed by rotation and other events. If this
     * method throws a ToastException, it will be displayed, and onPostExecute will not be
     * called. */
    abstract fun doInBackground(): Result

    /** This method is called on the UI thread after doInBackground returns. Unlike
     * onPreExecute, it may be called on a different fragment instance. */
    open fun onPostExecute(result: Result) {}
}


abstract class TaskLauncherDialog<Result> : AlertDialogFragment() {
    override fun onShowDialog(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            showDialog(activity!!, LaunchedTaskDialog<Result>().apply {
                setTargetFragment(this@TaskLauncherDialog, 0)
            })
        }
    }

    // See notes in TaskDialog.
    open fun onPreExecute() {}
    abstract fun doInBackground(): Result
    open fun onPostExecute(result: Result) {}
}


class LaunchedTaskDialog<Result> : TaskDialog<Result>() {
    @Suppress("UNCHECKED_CAST")
    val launcher by lazy { targetFragment as TaskLauncherDialog<Result> }

    override fun onPreExecute() = launcher.onPreExecute()
    override fun doInBackground() = launcher.doInBackground()

    override fun onPostExecute(result: Result) {
        launcher.onPostExecute(result)
        launcher.dismiss()
    }
}


abstract class PasswordDialog<Result> : TaskLauncherDialog<Result>() {
    var password: String by notNull()

    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.Enter_password)
            .setView(R.layout.password)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        return dialog
    }

    override fun onShowDialog(dialog: AlertDialog) {
        super.onShowDialog(dialog)
        dialog.etPassword.setOnEditorActionListener { _, actionId: Int, event: KeyEvent? ->
            // See comments in ConsoleActivity.createInput.
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                event?.action == KeyEvent.ACTION_UP) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            true
        }
    }

    override fun onPreExecute() {
        password = dialog.etPassword.text.toString()
    }

    override fun doInBackground(): Result {
        try {
            return onPassword(password)
        } catch (e: PyException) {
            throw if (e.message!!.startsWith("InvalidPassword"))
                ToastException(R.string.incorrect_password, Toast.LENGTH_SHORT) else e
        }
    }

    /** Attempt to perform the operation with the given password. If the operation fails, this
     * method should throw either a ToastException, or an InvalidPassword PyException (most
     * Python functions that take passwords will do this automatically). */
    abstract fun onPassword(password: String): Result
}
