package com.elegen.elegencashbook

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elegen.elegencashbook.core.ui.setPrimaryEnabled
import com.elegen.elegencashbook.data.remote.supabase.AuthDeepLinkHandler
import com.elegen.elegencashbook.feature.main.BookItem
import com.elegen.elegencashbook.feature.main.BusinessItem
import com.elegen.elegencashbook.feature.main.MainUiEvent
import com.elegen.elegencashbook.feature.main.MainUiState
import com.elegen.elegencashbook.feature.main.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var authDeepLinkHandler: AuthDeepLinkHandler

    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var booksAdapter: BookAdapter
    private lateinit var businessTitle: TextView
    private lateinit var tipCard: View
    private lateinit var emptyBooksCard: View

    private var uiState = MainUiState()
    private var businessSheetAdapter: BusinessAdapter? = null
    private var loginPromptShown = false
    private var tipDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthDeepLink(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        businessTitle = findViewById(R.id.business_title)
        val addBookButton = findViewById<MaterialButton>(R.id.add_book_button)
        val businessSelector = findViewById<View>(R.id.business_selector)
        booksRecyclerView = findViewById(R.id.books_list)
        tipCard = findViewById(R.id.tip_card)
        emptyBooksCard = findViewById(R.id.empty_books_card)

        booksAdapter = BookAdapter()
        booksRecyclerView.layoutManager = LinearLayoutManager(this)
        booksRecyclerView.adapter = booksAdapter

        // Top-bar icon: book-collaboration stub (P6). Account/login lives only behind "Settings".
        findViewById<ImageButton>(R.id.add_members_button).setOnClickListener {
            Toast.makeText(this, "Sharing books with members is coming soon", Toast.LENGTH_SHORT).show()
        }

        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
            .setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_settings -> { showAccountSheet(); false } // don't keep Settings selected
                    R.id.nav_help -> { Toast.makeText(this, "Help coming soon", Toast.LENGTH_SHORT).show(); false }
                    else -> true
                }
            }

        businessSelector.setOnClickListener { showBusinessSheet() }
        addBookButton.setOnClickListener {
            if (uiState.activeBusiness == null) {
                Toast.makeText(this, "Create a business first", Toast.LENGTH_SHORT).show()
                showBusinessSheet()
            } else {
                showAddBookSheet()
            }
        }

        findViewById<View>(R.id.tip_dismiss_button).setOnClickListener {
            tipDismissed = true
            tipCard.visibility = View.GONE
        }
        findViewById<View>(R.id.hero_dismiss_button).setOnClickListener {
            findViewById<View>(R.id.hero_card).visibility = View.GONE
        }

        listOf(
            findViewById<Chip>(R.id.chip_suggest_june),
            findViewById<Chip>(R.id.chip_suggest_petty),
            findViewById<Chip>(R.id.chip_suggest_project),
            findViewById<Chip>(R.id.chip_suggest_client),
        ).forEach { chip ->
            chip.setOnClickListener { quickAddBook(chip.text.toString()) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeepLink(intent)
    }

    /** Email-confirm / magic-link redirect lands here instead of a bare browser page. */
    private fun handleAuthDeepLink(intent: Intent) {
        authDeepLinkHandler.handle(
            intent = intent,
            onSuccess = { Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show() },
            onError = { Toast.makeText(this, "Sign-in link invalid or expired", Toast.LENGTH_SHORT).show() },
        )
    }

    private fun quickAddBook(name: String) {
        if (uiState.activeBusiness == null) {
            Toast.makeText(this, "Create a business first", Toast.LENGTH_SHORT).show()
            showBusinessSheet()
        } else {
            viewModel.onEvent(MainUiEvent.AddBook(name))
            booksRecyclerView.scrollToPosition(0)
        }
    }

    private fun render(state: MainUiState) {
        uiState = state
        businessTitle.text = state.activeBusiness?.name ?: "Add a business"
        booksAdapter.submit(state.books)
        businessSheetAdapter?.submit(state.businesses)

        if (state.books.isEmpty()) {
            tipCard.visibility = View.GONE
            emptyBooksCard.visibility = View.VISIBLE
        } else {
            tipCard.visibility = if (tipDismissed) View.GONE else View.VISIBLE
            emptyBooksCard.visibility = View.GONE
        }

        if (state.promptLogin && !loginPromptShown) {
            loginPromptShown = true
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    private fun showAccountSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_account, null)
        val account = uiState.account

        val identity = view.findViewById<View>(R.id.account_identity)
        val guestHint = view.findViewById<TextView>(R.id.account_guest_hint)
        val btnLogin = view.findViewById<MaterialButton>(R.id.btn_account_login)
        val btnLogout = view.findViewById<MaterialButton>(R.id.btn_account_logout)
        val btnLogoutWipe = view.findViewById<MaterialButton>(R.id.btn_account_logout_wipe)

        if (account.loggedIn) {
            identity.visibility = View.VISIBLE
            btnLogout.visibility = View.VISIBLE
            btnLogoutWipe.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.account_email).text = account.email ?: account.label
            view.findViewById<TextView>(R.id.account_phone).text = account.phone ?: "No phone added"

            btnLogout.setOnClickListener {
                viewModel.onEvent(MainUiEvent.SignOutKeepData)
                dialog.dismiss()
            }
            btnLogoutWipe.setOnClickListener {
                dialog.dismiss()
                confirmWipe()
            }
        } else {
            guestHint.visibility = View.VISIBLE
            btnLogin.visibility = View.VISIBLE
            btnLogin.setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        view.findViewById<ImageButton>(R.id.close_account_sheet).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("Remove all local data?")
            .setMessage("This signs you out and deletes every business, book and entry stored on this device. This cannot be undone here.")
            .setPositiveButton("Delete everything") { _, _ ->
                viewModel.onEvent(MainUiEvent.SignOutWipeData)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBookDetails(book: BookItem) {
        val intent = Intent(this, BookDetailsActivity::class.java).apply {
            putExtra("book_id", book.id)
            putExtra("book_name", book.name)
            putExtra("book_meta", book.metaText)
            putExtra("business_id", uiState.activeBusiness?.id)
        }
        startActivity(intent)
    }

    private fun showBusinessSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_business, null)
        val businessList = view.findViewById<RecyclerView>(R.id.business_list)
        val closeButton = view.findViewById<ImageButton>(R.id.close_business_sheet)
        val addBusinessButton = view.findViewById<MaterialButton>(R.id.add_business_button)

        businessList.layoutManager = LinearLayoutManager(this)
        addBusinessButton.setPrimaryEnabled(true)

        val adapter = BusinessAdapter { business ->
            viewModel.onEvent(MainUiEvent.SelectBusiness(business.id))
            dialog.dismiss()
        }
        adapter.submit(uiState.businesses)
        businessList.adapter = adapter
        businessSheetAdapter = adapter
        dialog.setOnDismissListener { businessSheetAdapter = null }

        closeButton.setOnClickListener { dialog.dismiss() }
        addBusinessButton.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddBusinessActivity::class.java))
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showAddBookSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_book, null)
        val bookNameInput = view.findViewById<TextInputEditText>(R.id.book_name_input)
        val addBookButton = view.findViewById<MaterialButton>(R.id.add_book_button)
        val bookNameLayout = view.findViewById<TextInputLayout>(R.id.book_name_layout)
        val closeButton = view.findViewById<ImageButton>(R.id.close_book_sheet)
        val suggestionChips = listOf(
            view.findViewById<Chip>(R.id.chip_june),
            view.findViewById<Chip>(R.id.chip_petty),
            view.findViewById<Chip>(R.id.chip_project),
            view.findViewById<Chip>(R.id.chip_client)
        )

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                addBookButton.setPrimaryEnabled(hasText)
                if (hasText) bookNameLayout.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        bookNameInput.addTextChangedListener(watcher)

        suggestionChips.forEach { chip ->
            chip.setOnClickListener {
                bookNameInput.setText(chip.text)
                bookNameInput.setSelection(bookNameInput.text?.length ?: 0)
            }
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        addBookButton.setOnClickListener {
            val name = bookNameInput.text?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) {
                viewModel.onEvent(MainUiEvent.AddBook(name))
                booksRecyclerView.scrollToPosition(0)
                dialog.dismiss()
            } else {
                bookNameLayout.error = "Name is required"
                bookNameLayout.boxStrokeColor = ContextCompat.getColor(this, R.color.danger_red)
                bookNameInput.requestFocus()
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private inner class BusinessAdapter(
        private val onSelected: (BusinessItem) -> Unit
    ) : RecyclerView.Adapter<BusinessAdapter.BusinessViewHolder>() {

        private val items = mutableListOf<BusinessItem>()

        fun submit(newItems: List<BusinessItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusinessViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_business, parent, false)
            return BusinessViewHolder(view)
        }

        override fun onBindViewHolder(holder: BusinessViewHolder, position: Int) {
            val business = items[position]
            holder.name.text = business.name
            holder.role.text = business.roleLabel
            holder.count.text = "${business.bookCount} ${if (business.bookCount == 1) "book" else "books"}"
            if (business.isActive) {
                holder.selected.setImageResource(R.drawable.ic_check_circle)
                holder.selected.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.success_green))
            } else {
                holder.selected.setImageResource(R.drawable.ic_radio_unchecked)
                holder.selected.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.unselected_gray))
            }
        }

        override fun getItemCount() = items.size

        private inner class BusinessViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.business_name)
            val role: TextView = view.findViewById(R.id.business_role)
            val count: TextView = view.findViewById(R.id.business_count)
            val selected: ImageView = view.findViewById(R.id.selected_indicator)

            init {
                view.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onSelected(items[position])
                    }
                }
            }
        }
    }

    private fun showBookMenu(anchor: View, book: BookItem) {
        val popupWidthPx = (180 * resources.displayMetrics.density).toInt()
        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_book_menu, null)
        val popupWindow = PopupWindow(popupView, popupWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        fun action(id: Int, block: () -> Unit) {
            popupView.findViewById<View>(id).setOnClickListener {
                popupWindow.dismiss()
                block()
            }
        }

        action(R.id.action_rename) { promptRenameBook(book) }
        action(R.id.action_duplicate) {
            viewModel.onEvent(MainUiEvent.DuplicateBook(book.id))
            Toast.makeText(this, "\"${book.name}\" duplicated", Toast.LENGTH_SHORT).show()
        }
        action(R.id.action_add_members) {
            Toast.makeText(this, "Sharing books with members is coming soon", Toast.LENGTH_SHORT).show()
        }
        action(R.id.action_move) { promptMoveBook(book) }
        action(R.id.action_delete) { confirmDeleteBook(book) }

        val xOffset = anchor.width - popupWidthPx
        popupWindow.showAsDropDown(anchor, xOffset, 4)
    }

    private fun promptRenameBook(book: BookItem) {
        val input = TextInputEditText(this).apply {
            setText(book.name)
            setSelection(text?.length ?: 0)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename book")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) viewModel.onEvent(MainUiEvent.RenameBook(book.id, name))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteBook(book: BookItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${book.name}\"?")
            .setMessage("You can undo right after deleting.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.onEvent(MainUiEvent.DeleteBook(book.id))
                Snackbar.make(booksRecyclerView, "Book deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") { viewModel.onEvent(MainUiEvent.RestoreBook(book.id)) }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMoveBook(book: BookItem) {
        val targets = uiState.businesses.filterNot { it.isActive }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Create another business first to move books into it", Toast.LENGTH_SHORT).show()
            return
        }
        val names = targets.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Move \"${book.name}\" to")
            .setItems(names) { _, index ->
                viewModel.onEvent(MainUiEvent.MoveBook(book.id, targets[index].id))
                Toast.makeText(this, "Moved to ${targets[index].name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class BookAdapter : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

        private val items = mutableListOf<BookItem>()

        fun submit(newItems: List<BookItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return BookViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val book = items[position]
            holder.name.text = book.name
            holder.meta.text = book.metaText
            holder.balance.text = book.balanceText
            holder.itemView.setOnClickListener { showBookDetails(book) }
            holder.menu.setOnClickListener { showBookMenu(it, book) }
        }

        override fun getItemCount() = items.size

        private inner class BookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.book_name)
            val meta: TextView = view.findViewById(R.id.book_meta)
            val balance: TextView = view.findViewById(R.id.book_balance)
            val menu: ImageButton = view.findViewById(R.id.book_menu)
        }
    }
}
