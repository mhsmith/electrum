package org.electroncash.electroncash3

import android.annotation.SuppressLint
import android.app.Dialog
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProviders
import android.content.DialogInterface
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import kotlinx.android.synthetic.main.new_wallet.*
import kotlinx.android.synthetic.main.seed.*
import kotlinx.android.synthetic.main.transaction_detail.*
import kotlinx.android.synthetic.main.wallets.*
import org.electroncash.electroncash3.databinding.WalletsBinding
import kotlin.math.roundToInt


class WalletsFragment : Fragment(), MainFragment {
    override val title = MutableLiveData<String>()
    override val subtitle = MutableLiveData<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        daemonModel.netStatus.observe(this, Observer { status ->
            if (status != null) {
                title.value = getString(R.string.online)
                subtitle.value = if (status.localHeight < status.serverHeight) {
                    "${getString(R.string.synchronizing)} ${status.localHeight} / ${status.serverHeight}"
                } else {
                    "${getString(R.string.height)} ${status.localHeight}"
                }
            } else {
                title.value = getString(R.string.offline)
                subtitle.value = getString(R.string.cannot_send)
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.wallets, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        if (daemonModel.walletName.value == null) {
            menu.clear()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menuShowSeed-> showDialog(activity!!, ShowSeedPasswordDialog())
            R.id.menuDelete -> showDialog(activity!!, DeleteWalletDialog())
            R.id.menuClose -> daemonModel.commands.callAttr("close_wallet")
            else -> throw Exception("Unknown item $item")
        }
        return true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val binding = WalletsBinding.inflate(inflater, container, false)
        binding.setLifecycleOwner(this)
        binding.model = daemonModel
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        walletPanel.setOnClickListener {
            showDialog(activity!!, SelectWalletDialog())
        }
        daemonModel.walletBalance.observe(viewLifecycleOwner, Observer { balance ->
            tvBalance.text = if (balance == null) "" else formatSatoshis(balance)
            tvBalanceUnit.text = when {
                daemonModel.wallet == null -> getString(R.string.touch_to_load)
                balance == null -> getString(R.string.synchronizing)
                else -> unitName
            }
            updateFiat()
        })
        fiatUpdate.observe(viewLifecycleOwner, Observer { updateFiat() })

        setupVerticalList(rvTransactions)
        daemonModel.transactions.observe(viewLifecycleOwner, Observer {
            rvTransactions.adapter = if (it == null) null
                                     else TransactionsAdapter(activity!!, it)
        })

        daemonModel.walletName.observe(viewLifecycleOwner, Observer {
            activity!!.invalidateOptionsMenu()
            if (it == null) {
                btnSend.hide()
            } else {
                btnSend.show()
            }
        })
        btnSend.setOnClickListener { showDialog(activity!!, SendDialog()) }
    }

    fun updateFiat() {
        val balance = daemonModel.walletBalance.value
        val fiat = if (balance == null) null else formatFiatAmountAndUnit(balance)
        tvFiat.text = if (fiat == null) "" else "($fiat)"
    }
}


// TODO integrate into Wallets screen like in the iOS app.
class SelectWalletDialog : AlertDialogFragment(), DialogInterface.OnClickListener {
    lateinit var items: MutableList<String>

    override fun onBuildDialog(builder: AlertDialog.Builder) {
        items = daemonModel.listWallets()
        items.add(getString(R.string.new_wallet))
        builder.setTitle(R.string.wallets)
            .setSingleChoiceItems(items.toTypedArray(),
                                  items.indexOf(daemonModel.walletName.value), this)
    }

    override fun onResume() {
        super.onResume()
        if (items.size == 1) {
            onClick(dialog, 0)
        }
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        dismiss()
        if (which < items.size - 1) {
            showDialog(activity!!, OpenWalletDialog().apply { arguments = Bundle().apply {
                putString("walletName", items[which])
            }})
        } else {
            showDialog(activity!!, NewWalletDialog())
        }
    }
}


class NewWalletDialog : AlertDialogFragment() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.new_wallet)
            .setView(R.layout.new_wallet)
            .setPositiveButton(R.string.next, null)
            .setNegativeButton(R.string.cancel, null)
    }

    override fun onShowDialog(dialog: AlertDialog) {
        dialog.spnType.adapter = MenuAdapter(context!!, R.menu.wallet_type)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val name = dialog.etName.text.toString()
                if (name.isEmpty()) throw ToastException(R.string.name_is)
                if (name.contains("/")) throw ToastException(R.string.invalid_name)
                if (daemonModel.listWallets().contains(name)) {
                    throw ToastException(R.string.a_wallet_with_that_name_already_exists_please)
                }

                val password = dialog.etPassword.text.toString()
                if (password.isEmpty()) throw ToastException(R.string.enter_password)
                if (password != dialog.etConfirmPassword.text.toString()) {
                    throw ToastException(R.string.wallet_passwords)
                }

                val seed = when (dialog.spnType.selectedItemId.toInt()) {
                    R.id.menuCreateSeed -> daemonModel.commands.callAttr("make_seed").toString()
                    R.id.menuRestoreSeed -> null
                    else -> throw Exception("Unknown item " + dialog.spnType.selectedItem)
                }
                showDialog(activity!!, NewSeedDialog().apply { arguments = Bundle().apply {
                    putString("name", name)
                    putString("password", password)
                    putString("seed", seed)
                }})
                dismiss()
            } catch (e: ToastException) { e.show() }
        }
    }
}

class NewSeedDialog : SeedDialog() {
    class Model : ViewModel() {
        val result = MutableLiveData<Boolean>()
    }
    private val model by lazy { ViewModelProviders.of(this).get(Model::class.java) }

    override fun onShowDialog(dialog: AlertDialog) {
        super.onShowDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            model.result.value = null
            showDialog(activity!!, ProgressDialogFragment())
            Thread {
                try {
                    val name = arguments!!.getString("name")!!
                    val password = arguments!!.getString("password")
                    val seed = dialog.etSeed.text.toString()
                    try {
                        daemonModel.commands.callAttr("create", name, password, seed)
                    } catch (e: PyException) {
                        if (e.message!!.startsWith("InvalidSeed")) {
                            throw ToastException(R.string.the_seed_you_entered_does_not_appear)
                        }
                        throw e
                    }
                    daemonModel.loadWallet(name, password)
                    model.result.postValue(true)
                } catch (e: ToastException) {
                    e.show()
                    model.result.postValue(false)
                }
            }.start()
        }
        model.result.observe(this, Observer { onResult(it) })
    }

    fun onResult(success: Boolean?) {
        if (success == null) return
        dismissDialog(activity!!, ProgressDialogFragment::class)
        if (success) {
            dismiss()
        }
    }
}


class DeleteWalletDialog : AlertDialogFragment() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        val walletName = daemonModel.walletName.value
        val message = getString(R.string.do_you_want_to_delete, walletName) + "\n\n" +
                      getString(R.string.if_your_wallet)
        builder.setTitle(R.string.delete_wallet)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                daemonModel.commands.callAttr("delete_wallet", walletName)
            }
            .setNegativeButton(android.R.string.cancel, null)
    }
}


abstract class PasswordDialog(val runInBackground: Boolean = false) : AlertDialogFragment() {
    class Model : ViewModel() {
        val result = MutableLiveData<Boolean>()
    }
    private val model by lazy { ViewModelProviders.of(this).get(Model::class.java) }

    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.password_required)
            .setView(R.layout.password)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        return dialog
    }

    override fun onShowDialog(dialog: AlertDialog) {
        val posButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        posButton.setOnClickListener {
            tryPassword(dialog.etPassword.text.toString())
        }
        dialog.etPassword.setOnEditorActionListener { _, _, _ ->
            posButton.performClick()
        }
        model.result.observe(this, Observer { onResult(it) })
    }

    fun tryPassword(password: String) {
        model.result.value = null
        val r = Runnable {
            try {
                try {
                    onPassword(password)
                    model.result.postValue(true)
                } catch (e: PyException) {
                    throw if (e.message!!.startsWith("InvalidPassword"))
                        ToastException(R.string.password_incorrect, Toast.LENGTH_SHORT) else e
                }
            } catch (e: ToastException) {
                e.show()
                model.result.postValue(false)
            }
        }
        if (runInBackground) {
            showDialog(activity!!, ProgressDialogFragment())
            Thread(r).start()
        } else {
            r.run()
        }
    }

    /** Attempt to perform the operation with the given password. If the operation fails, this
     * method should throw either a ToastException, or an InvalidPassword PyException (most
     * lib functions that take passwords will do this automatically). */
    abstract fun onPassword(password: String)

    private fun onResult(success: Boolean?) {
        if (success == null) return
        dismissDialog(activity!!, ProgressDialogFragment::class)
        if (success) {
            dismiss()
        }
    }
}


class OpenWalletDialog: PasswordDialog(runInBackground = true) {
    override fun onPassword(password: String) {
        daemonModel.loadWallet(arguments!!.getString("walletName")!!, password)
    }
}


class ShowSeedPasswordDialog : PasswordDialog() {
    override fun onPassword(password: String) {
        val seed = daemonModel.wallet!!.callAttr("get_seed", password).toString()
        if (! seed.contains(" ")) {
            // get_seed(None) doesn't throw an exception, but returns the encrypted base64 seed.
            throw PyException("InvalidPassword")
        }
        showDialog(activity!!, SeedDialog().apply { arguments = Bundle().apply {
            putString("seed", seed)
        }})
    }
}

open class SeedDialog : AlertDialogFragment() {
    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.wallet_seed)
            .setView(R.layout.seed)
            .setPositiveButton(android.R.string.ok, null)
    }

    override fun onShowDialog(dialog: AlertDialog) {
        val seed = arguments!!.getString("seed")
        if (seed == null) {
            dialog.tvSeedLabel.setText(R.string.please_enter_your_seed_phrase)
        } else {
            dialog.tvSeedLabel.setText(seedAdvice(seed))
            dialog.etSeed.setText(seed)
            dialog.etSeed.setFocusable(false)
        }
    }
}


fun seedAdvice(seed: String): String {
    return app.getString(R.string.please_save, seed.split(" ").size) + " " +
           app.getString(R.string.this_seed) + " " +
           app.getString(R.string.never_disclose)
}


class TransactionsAdapter(val activity: FragmentActivity, val transactions: PyObject)
    : BoundAdapter<TransactionModel>(R.layout.transaction_list) {

    override fun getItem(position: Int): TransactionModel {
        val t = transactions.callAttr("__getitem__", itemCount - position - 1)
        // TODO: simplify this once Chaquopy provides better syntax for dict access
        return TransactionModel(
            t.callAttr("__getitem__", "txid").toString(),
            t.callAttr("__getitem__", "value").toString(),
            t.callAttr("__getitem__", "balance").toString(),
            t.callAttr("__getitem__", "date").toString(),
            t.callAttr("__getitem__", "confirmations").toJava(Int::class.java))
    }

    override fun getItemCount(): Int {
        return transactions.callAttr("__len__").toJava(Int::class.java)
    }

    override fun onBindViewHolder(holder: BoundViewHolder<TransactionModel>, position: Int) {
        super.onBindViewHolder(holder, position)
        holder.itemView.setOnClickListener {
            showDialog(activity, TransactionDialog(holder.item.txid))
        }
    }
}

class TransactionModel(
    val txid: String,
    val value: String,
    val balance: String,
    val date: String,
    val confirmations: Int) {

    @SuppressLint("StringFormatMatches")
    val confirmationsStr = when {
        confirmations <= 0 -> ""
        confirmations > 6 -> app.getString(R.string.confirmed)
        else -> app.getString(R.string.___confirmations, confirmations)
    }
}


class TransactionDialog() : AlertDialogFragment() {
    constructor(txid: String) : this() {
        arguments = Bundle().apply { putString("txid", txid) }
    }
    val txid by lazy { arguments!!.getString("txid")!! }
    val wallet by lazy { daemonModel.wallet!! }

    override fun onBuildDialog(builder: AlertDialog.Builder) {
        builder.setView(R.layout.transaction_detail)
            .setPositiveButton(R.string.ok, null)
    }

    override fun onShowDialog(dialog: AlertDialog) {
        dialog.btnExplore.setOnClickListener { exploreTransaction(activity!!, txid) }
        dialog.btnCopy.setOnClickListener { copyToClipboard(txid) }

        val tx = wallet.get("transactions")!!.callAttr("get", txid)!!
        val txInfo = wallet.callAttr("get_tx_info", tx)
        dialog.tvTxid.text = txid

        val timestamp = txInfo.callAttr("__getitem__", 8).toJava(Long::class.java)
        dialog.tvTimestamp.text = if (timestamp == 0L) getString(R.string.Unconfirmed)
                                  else libUtil.callAttr("format_time", timestamp).toString()

        dialog.tvStatus.text = txInfo.callAttr("__getitem__", 1).toString()

        val size = tx.callAttr("estimated_size").toJava(Int::class.java)
        dialog.tvSize.text = getString(R.string.bytes, size)

        val fee = txInfo.callAttr("__getitem__", 5)?.toJava(Long::class.java)
        if (fee == null) {
            dialog.tvFee.text = getString(R.string.Unknown)
        } else {
            val feeSpb = (fee.toDouble() / size.toDouble()).roundToInt()
            dialog.tvFee.text = String.format("%s (%s %s)",
                                              getString(R.string.sat_byte, feeSpb),
                                              formatSatoshis(fee), unitName)
        }
    }
}