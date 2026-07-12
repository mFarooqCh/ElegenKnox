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
import com.elegen.elegencashbook.data.remote.supabase.RealtimeSync
import com.elegen.elegencashbook.domain.model.HistoryEntityType
import com.elegen.elegencashbook.domain.usecase.GetEntityHistory
import com.elegen.elegencashbook.feature.history.toHistoryItem
import com.elegen.elegencashbook.feature.main.BookItem
import com.elegen.elegencashbook.feature.main.BusinessItem
import com.elegen.elegencashbook.feature.main.MainUiEvent
import com.elegen.elegencashbook.feature.main.MainUiState
import com.elegen.elegencashbook.feature.main.MainViewModel
import com.elegen.elegencashbook.ui.DeleteConfirmDialog
import com.elegen.elegencashbook.ui.HistoryDialog
import com.elegen.elegencashbook.ui.PickTargetDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var authDeepLinkHandler: AuthDeepLinkHandler
    @Inject lateinit var getEntityHistory: GetEntityHistory
    @Inject lateinit var realtimeSync: RealtimeSync

    private val historyDateFmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var booksAdapter: BookAdapter
    private lateinit var businessTitle: TextView
    private lateinit var tipCard: View
    private lateinit var emptyBooksCard: View

    private var uiState = MainUiState()
    private var businessSheetOwnedAdapter: BusinessAdapter? = null
    private var businessSheetSharedAdapter: BusinessAdapter? = null
    private var businessSheetSharedLabel: TextView? = null
    private var businessSheetSharedList: RecyclerView? = null
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

        findViewById<ImageButton>(R.id.add_members_button).setOnClickListener {
            val business = uiState.activeBusiness
            if (business == null) {
                Toast.makeText(this, "Add a business first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, MembersActivity::class.java)
                    .putExtra("business_id", business.id)
                    .putExtra("business_name", business.name)
            )
        }

        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
            .setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); false } // don't keep Settings selected
                    R.id.nav_help -> { startActivity(Intent(this, HelpActivity::class.java)); false }
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
                viewModel.state.collect { state ->
                    render(state)
                    // Foreground-only live updates (spec §6.4 P7). setActiveBusiness no-ops on a
                    // repeat call and RealtimeSync itself tracks true app foreground/background
                    // via ProcessLifecycleOwner — not this Activity's onStop, which used to kill
                    // the subscription the moment any other Activity (e.g. BookDetailsActivity)
                    // came to the front, even with the app still fully foregrounded.
                    realtimeSync.setActiveBusiness(state.activeBusiness?.id)
                }
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
        businessSheetOwnedAdapter?.submit(state.businesses.filter { it.isOwner })
        val shared = state.businesses.filterNot { it.isOwner }
        businessSheetSharedAdapter?.submit(shared)
        businessSheetSharedLabel?.visibility = if (shared.isEmpty()) View.GONE else View.VISIBLE
        businessSheetSharedList?.visibility = if (shared.isEmpty()) View.GONE else View.VISIBLE

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

        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.onEvent(MainUiEvent.ErrorShown)
        }
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
        val ownedList = view.findViewById<RecyclerView>(R.id.business_list_owned)
        val sharedList = view.findViewById<RecyclerView>(R.id.business_list_shared)
        val sharedLabel = view.findViewById<TextView>(R.id.label_shared_businesses)
        val closeButton = view.findViewById<ImageButton>(R.id.close_business_sheet)
        val addBusinessButton = view.findViewById<MaterialButton>(R.id.add_business_button)

        ownedList.layoutManager = LinearLayoutManager(this)
        sharedList.layoutManager = LinearLayoutManager(this)
        addBusinessButton.setPrimaryEnabled(true)

        fun onSelected(business: BusinessItem) {
            viewModel.onEvent(MainUiEvent.SelectBusiness(business.id))
            dialog.dismiss()
        }
        fun onSettings(business: BusinessItem) {
            dialog.dismiss()
            startActivity(
                Intent(this, BusinessSettingsActivity::class.java)
                    .putExtra("business_id", business.id)
                    .putExtra("business_name", business.name)
            )
        }

        val ownedAdapter = BusinessAdapter(::onSelected, ::onSettings)
        val sharedAdapter = BusinessAdapter(::onSelected, ::onSettings)
        ownedAdapter.submit(uiState.businesses.filter { it.isOwner })
        val shared = uiState.businesses.filterNot { it.isOwner }
        sharedAdapter.submit(shared)
        sharedLabel.visibility = if (shared.isEmpty()) View.GONE else View.VISIBLE
        sharedList.visibility = if (shared.isEmpty()) View.GONE else View.VISIBLE

        ownedList.adapter = ownedAdapter
        sharedList.adapter = sharedAdapter
        businessSheetOwnedAdapter = ownedAdapter
        businessSheetSharedAdapter = sharedAdapter
        businessSheetSharedLabel = sharedLabel
        businessSheetSharedList = sharedList
        dialog.setOnDismissListener {
            businessSheetOwnedAdapter = null
            businessSheetSharedAdapter = null
            businessSheetSharedLabel = null
            businessSheetSharedList = null
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        addBusinessButton.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddBusinessActivity::class.java))
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showAddBookSheet(bookToRename: BookItem? = null) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_book, null)
        val title = view.findViewById<TextView>(R.id.book_sheet_title)
        val bookNameInput = view.findViewById<TextInputEditText>(R.id.book_name_input)
        val addBookButton = view.findViewById<MaterialButton>(R.id.add_book_button)
        val bookNameLayout = view.findViewById<TextInputLayout>(R.id.book_name_layout)
        val closeButton = view.findViewById<ImageButton>(R.id.close_book_sheet)
        val suggestionsLabel = view.findViewById<TextView>(R.id.book_suggestions_label)
        val suggestionsGroup = view.findViewById<View>(R.id.book_suggestions)
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

        if (bookToRename != null) {
            title.text = "Rename Book"
            addBookButton.text = "RENAME"
            suggestionsLabel.visibility = View.GONE
            suggestionsGroup.visibility = View.GONE
            bookNameInput.setText(bookToRename.name)
            bookNameInput.setSelection(bookNameInput.text?.length ?: 0)
            addBookButton.setPrimaryEnabled(true)
        } else {
            suggestionChips.forEach { chip ->
                chip.setOnClickListener {
                    bookNameInput.setText(chip.text)
                    bookNameInput.setSelection(bookNameInput.text?.length ?: 0)
                }
            }
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        addBookButton.setOnClickListener {
            val name = bookNameInput.text?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) {
                if (bookToRename != null) {
                    viewModel.onEvent(MainUiEvent.RenameBook(bookToRename.id, name))
                } else {
                    viewModel.onEvent(MainUiEvent.AddBook(name))
                    booksRecyclerView.scrollToPosition(0)
                }
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
        private val onSelected: (BusinessItem) -> Unit,
        private val onSettings: (BusinessItem) -> Unit,
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
            holder.role.text = business.roleLabel.uppercase()
            val (roleBg, roleText) = when (business.roleLabel) {
                "Owner" -> R.color.icon_chip_bg to R.color.brand
                "Admin" -> R.color.success_green_bg to R.color.success_green
                else -> R.color.divider_light to R.color.text_muted
            }
            holder.rolePill.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, roleBg)
            holder.role.setTextColor(ContextCompat.getColor(this@MainActivity, roleText))
            holder.count.text = "${business.bookCount} ${if (business.bookCount == 1) "book" else "books"}"
            if (business.isActive) {
                holder.selected.setImageResource(R.drawable.ic_check_circle)
                holder.selected.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.success_green))
                holder.itemView.setBackgroundResource(R.drawable.bg_target_row_selected)
            } else {
                holder.selected.setImageResource(R.drawable.ic_radio_unchecked)
                holder.selected.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.unselected_gray))
                holder.itemView.setBackgroundResource(R.drawable.bg_target_row_unselected)
            }
        }

        override fun getItemCount() = items.size

        private inner class BusinessViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.business_name)
            val role: TextView = view.findViewById(R.id.business_role)
            val rolePill: View = view.findViewById(R.id.business_role_pill)
            val count: TextView = view.findViewById(R.id.business_count)
            val selected: ImageView = view.findViewById(R.id.selected_indicator)

            init {
                view.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onSelected(items[position])
                    }
                }
                view.findViewById<ImageButton>(R.id.business_settings_gear).setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onSettings(items[position])
                    }
                }
                // Truncated name (maxWidth in item_business.xml) — tap it to see the full name
                // instead of switching business, since that's what the row itself already does.
                name.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        Toast.makeText(this@MainActivity, items[position].name, Toast.LENGTH_LONG).show()
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

        action(R.id.action_rename) { showAddBookSheet(bookToRename = book) }
        action(R.id.action_duplicate) {
            viewModel.onEvent(MainUiEvent.DuplicateBook(book.id))
            Toast.makeText(this, "\"${book.name}\" duplicated", Toast.LENGTH_SHORT).show()
        }
        action(R.id.action_add_members) {
            startActivity(
                Intent(this, BookAccessActivity::class.java)
                    .putExtra("book_id", book.id)
                    .putExtra("book_name", book.name)
                    .putExtra("business_id", uiState.activeBusiness?.id)
            )
        }
        action(R.id.action_move) { promptMoveBook(book) }
        action(R.id.action_history) {
            lifecycleScope.launch {
                val items = getEntityHistory(HistoryEntityType.BOOK, book.id).first().map { it.toHistoryItem(historyDateFmt) }
                HistoryDialog.show(this@MainActivity, "\"${book.name}\" History", items)
            }
        }
        action(R.id.action_delete) { confirmDeleteBook(book) }

        val xOffset = anchor.width - popupWidthPx
        popupWindow.showAsDropDown(anchor, xOffset, 4)
    }

    private fun confirmDeleteBook(book: BookItem) {
        DeleteConfirmDialog.show(
            context = this,
            title = "Delete this book?",
            subtitle = "All entries inside will be permanently deleted. This can't be undone.",
            stat1 = DeleteConfirmDialog.Stat("1", "Book"),
            stat2 = DeleteConfirmDialog.Stat("${book.entryCount}", "Entries"),
        ) {
            viewModel.onEvent(MainUiEvent.DeleteBook(book.id))
            Snackbar.make(booksRecyclerView, "Book deleted", Snackbar.LENGTH_LONG)
                .setAction("Undo") { viewModel.onEvent(MainUiEvent.RestoreBook(book.id)) }
                .show()
        }
    }

    private fun promptMoveBook(book: BookItem) {
        val targets = uiState.businesses.filterNot { it.isActive }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Create another business first to move books into it", Toast.LENGTH_SHORT).show()
            return
        }
        PickTargetDialog.show(
            context = this,
            iconRes = R.drawable.ic_move,
            headerTitle = "Move \"${book.name}\" to",
            headerSubtitle = "Book leaves its current business",
            items = targets.map { PickTargetDialog.Item(it.id, it.name, "${it.bookCount} books") },
        ) { picked ->
            viewModel.onEvent(MainUiEvent.MoveBook(book.id, picked.id))
            Toast.makeText(this, "Moved to ${picked.title}", Toast.LENGTH_SHORT).show()
        }
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
            holder.balance.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (book.balanceIsNegative) R.color.danger_red else R.color.success_green
                )
            )
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
