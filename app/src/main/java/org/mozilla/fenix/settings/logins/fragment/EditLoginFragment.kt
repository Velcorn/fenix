/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.logins.fragment

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.menu.ActionMenuItemView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import kotlinx.android.synthetic.main.fragment_edit_login.*
import kotlinx.android.synthetic.main.fragment_edit_login.view.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mozilla.components.lib.state.ext.consumeFrom
import mozilla.components.support.ktx.android.view.hideKeyboard
import org.mozilla.fenix.R
import org.mozilla.fenix.components.StoreProvider
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.ext.redirectToReAuth
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.settings.logins.LoginsAction
import org.mozilla.fenix.settings.logins.LoginsFragmentStore
import org.mozilla.fenix.settings.logins.LoginsListState
import org.mozilla.fenix.settings.logins.SavedLogin
import org.mozilla.fenix.settings.logins.controller.SavedLoginsStorageController
import org.mozilla.fenix.settings.logins.interactor.EditLoginInteractor
import org.mozilla.fenix.settings.logins.view.EditLoginView

/**
 * Displays the editable saved login information for a single website
 */
@ExperimentalCoroutinesApi
@Suppress("TooManyFunctions", "NestedBlockDepth", "ForbiddenComment")
class EditLoginFragment : Fragment(R.layout.fragment_edit_login) {

    fun String.toEditable(): Editable = Editable.Factory.getInstance().newEditable(this)

    private val args by navArgs<EditLoginFragmentArgs>()
    private lateinit var loginsFragmentStore: LoginsFragmentStore
    private lateinit var interactor: EditLoginInteractor
    private lateinit var editLoginView: EditLoginView
    private lateinit var oldLogin: SavedLogin

    private var listOfPossibleDupes: List<SavedLogin>? = null

    private var usernameChanged = false
    private var passwordChanged = false
    private var saveEnabled = false
    private var showPassword = true

    private var validPassword = true
    private var validUsername = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        oldLogin = args.savedLoginItem
        editLoginView = EditLoginView(view.editLoginLayout)

        loginsFragmentStore = StoreProvider.get(this) {
            LoginsFragmentStore(
                LoginsListState(
                    isLoading = true,
                    loginList = listOf(),
                    filteredItems = listOf(),
                    searchedForText = null,
                    sortingStrategy = requireContext().settings().savedLoginsSortingStrategy,
                    highlightedItem = requireContext().settings().savedLoginsMenuHighlightedItem,
                    duplicateLogins = listOf()
                )
            )
        }

        interactor = EditLoginInteractor(
            SavedLoginsStorageController(
                context = requireContext(),
                viewLifecycleScope = viewLifecycleOwner.lifecycleScope,
                navController = findNavController(),
                loginsFragmentStore = loginsFragmentStore
            )
        )

        loginsFragmentStore.dispatch(LoginsAction.UpdateCurrentLogin(args.savedLoginItem))
        interactor.findPotentialDuplicates(args.savedLoginItem.guid)

        // initialize editable values
        hostnameText.text = args.savedLoginItem.origin.toEditable()
        usernameText.text = args.savedLoginItem.username.toEditable()
        passwordText.text = args.savedLoginItem.password.toEditable()

        formatEditableValues()
        initSaveState()
        setUpClickListeners()
        setUpTextListeners()
        editLoginView.showPassword()

        consumeFrom(loginsFragmentStore) {
            listOfPossibleDupes = loginsFragmentStore.state.duplicateLogins
        }
    }

    private fun initSaveState() {
        saveEnabled = false // don't enable saving until something has been changed
        val saveButton =
            activity?.findViewById<ActionMenuItemView>(R.id.save_login_button)
        saveButton?.isEnabled = saveEnabled

        usernameChanged = false
        passwordChanged = false
    }

    private fun formatEditableValues() {
        hostnameText.isClickable = false
        hostnameText.isFocusable = false
        usernameText.inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // TODO: extend PasswordTransformationMethod() to change bullets to asterisks
        passwordText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordText.compoundDrawablePadding =
            requireContext().resources
                .getDimensionPixelOffset(R.dimen.saved_logins_end_icon_drawable_padding)
    }

    private fun setUpClickListeners() {
        clearUsernameTextButton.setOnClickListener {
            usernameText.text?.clear()
            usernameText.isCursorVisible = true
            usernameText.hasFocus()
            inputLayoutUsername.hasFocus()
            it.isEnabled = false
        }
        clearPasswordTextButton.setOnClickListener {
            passwordText.text?.clear()
            passwordText.isCursorVisible = true
            passwordText.hasFocus()
            inputLayoutPassword.hasFocus()
            it.isEnabled = false
        }
        revealPasswordButton.setOnClickListener {
            showPassword = !showPassword
            if (showPassword) {
                editLoginView.showPassword()
            } else {
                editLoginView.hidePassword()
            }
        }
    }

    private fun setUpTextListeners() {
        val frag = view?.findViewById<View>(R.id.editLoginFragment)
        frag?.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view?.hideKeyboard()
            }
        }
        editLoginLayout.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                view?.hideKeyboard()
            }
        }

        usernameText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(u: Editable?) {
                when {
                    u.toString() == oldLogin.username -> {
                        usernameChanged = false
                        validUsername = true
                        inputLayoutUsername.error = null
                        inputLayoutUsername.errorIconDrawable = null
                    }
                    else -> {
                        usernameChanged = true
                        clearUsernameTextButton.isEnabled = true
                        setDupeError()
                    }
                }
                setSaveButtonState()
            }

            override fun beforeTextChanged(u: CharSequence?, start: Int, count: Int, after: Int) {
                // NOOP
            }

            override fun onTextChanged(u: CharSequence?, start: Int, before: Int, count: Int) {
                // NOOP
            }
        })

        passwordText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p: Editable?) {
                when {
                    p.toString().isEmpty() -> {
                        passwordChanged = true
                        clearPasswordTextButton.isEnabled = false
                        setPasswordError()
                    }
                    p.toString() == oldLogin.password -> {
                        passwordChanged = false
                        validPassword = true
                        inputLayoutPassword.error = null
                        inputLayoutPassword.errorIconDrawable = null
                        clearPasswordTextButton.isEnabled = true
                    }
                    else -> {
                        passwordChanged = true
                        validPassword = true
                        inputLayoutPassword.error = null
                        inputLayoutPassword.errorIconDrawable = null
                        clearPasswordTextButton.isEnabled = true
                    }
                }
                setSaveButtonState()
            }

            override fun beforeTextChanged(p: CharSequence?, start: Int, count: Int, after: Int) {
                // NOOP
            }

            override fun onTextChanged(p: CharSequence?, start: Int, before: Int, count: Int) {
                // NOOP
            }
        })
    }

    private fun isDupe(username: String): Boolean =
        loginsFragmentStore.state.duplicateLogins.filter { it.username == username }.any()

    private fun setDupeError() {
        if (isDupe(usernameText.text.toString())) {
            inputLayoutUsername?.let {
                usernameChanged = true
                validUsername = false
                it.setErrorIconDrawable(R.drawable.mozac_ic_warning)
                it.error = context?.getString(R.string.saved_login_duplicate)
            }
        } else {
            usernameChanged = true
            validUsername = true
            inputLayoutUsername.error = null
        }
    }

    private fun setPasswordError() {
        inputLayoutPassword?.let { layout ->
            validPassword = false
            layout.error = context?.getString(R.string.saved_login_password_required)
            layout.setErrorIconDrawable(R.drawable.mozac_ic_warning)
        }
    }

    private fun setSaveButtonState() {
        val saveButton = activity?.findViewById<ActionMenuItemView>(R.id.save_login_button)
        val changesMadeWithNoErrors =
            validUsername && validPassword && (usernameChanged || passwordChanged)

        changesMadeWithNoErrors.let {
            saveButton?.isEnabled = it
            saveEnabled = it
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.login_save, menu)
    }

    override fun onPause() {
        redirectToReAuth(
            listOf(R.id.loginDetailFragment),
            findNavController().currentDestination?.id
        )
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.save_login_button -> {
            view?.hideKeyboard()
            if (saveEnabled) {
                interactor.onSaveLogin(
                    args.savedLoginItem.guid,
                    usernameText.text.toString(),
                    passwordText.text.toString()
                )
                requireComponents.analytics.metrics.track(Event.EditLoginSave)
            }
            true
        }
        else -> false
    }
}
