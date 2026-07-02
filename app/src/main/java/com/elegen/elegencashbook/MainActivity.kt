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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elegen.elegencashbook.feature.main.BookItem
import com.elegen.elegencashbook.feature.main.BusinessItem
import com.elegen.elegencashbook.feature.main.MainUiEvent
import com.elegen.elegencashbook.feature.main.MainUiState
import com.elegen.elegencashbook.feature.main.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var booksAdapter: BookAdapter
    private lateinit var businessTitle: TextView

    private var uiState = MainUiState()
    private var businessSheetAdapter: BusinessAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        booksAdapter = BookAdapter()
        booksRecyclerView.layoutManager = LinearLayoutManager(this)
        booksRecyclerView.adapter = booksAdapter

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
            findViewById<View>(R.id.tip_card).visibility = View.GONE
        }
        findViewById<View>(R.id.hero_dismiss_button).setOnClickListener {
            findViewById<View>(R.id.hero_card).visibility = View.GONE
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MainUiState) {
        uiState = state
        businessTitle.text = state.activeBusiness?.name ?: "Add a business"
        booksAdapter.submit(state.books)
        businessSheetAdapter?.submit(state.businesses)
    }

    private fun showBookDetails(book: BookItem) {
        val intent = Intent(this, BookDetailsActivity::class.java).apply {
            putExtra("book_id", book.id)
            putExtra("book_name", book.name)
            putExtra("book_meta", book.metaText)
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
        addBusinessButton.isEnabled = true
        addBusinessButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
        addBusinessButton.setTextColor(android.graphics.Color.WHITE)

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
                addBookButton.isEnabled = hasText
                if (hasText) {
                    bookNameLayout.error = null
                    addBookButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
                    addBookButton.setTextColor(android.graphics.Color.WHITE)
                } else {
                    addBookButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E5E7EB"))
                    addBookButton.setTextColor(android.graphics.Color.parseColor("#6B7280"))
                }
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
                bookNameLayout.boxStrokeColor = android.graphics.Color.parseColor("#DC2626")
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
                holder.selected.setColorFilter(0xFF16A34A.toInt())
            } else {
                holder.selected.setImageResource(R.drawable.ic_radio_unchecked)
                holder.selected.setColorFilter(0xFFD1D5DB.toInt())
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

        fun action(id: Int, message: String) {
            popupView.findViewById<View>(id).setOnClickListener {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                popupWindow.dismiss()
            }
        }

        action(R.id.action_rename, "Rename ${book.name}")
        action(R.id.action_duplicate, "Duplicate ${book.name}")
        action(R.id.action_add_members, "Add Members to ${book.name}")
        action(R.id.action_move, "Move ${book.name}")
        action(R.id.action_delete, "Delete ${book.name}")

        val xOffset = anchor.width - popupWidthPx
        popupWindow.showAsDropDown(anchor, xOffset, 4)
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
