package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.FragmentFirstBinding
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.components.FragmentComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
@AndroidEntryPoint
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private val viewModel: FoodListContract.ViewModel by viewModels { viewModelFactory }

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding?.editText?.addTextChangedListener {
            viewModel.onSearch(it?.toString().orEmpty())
        }
        _binding?.recyclerView?.apply {
            val foodAdapter = FoodAdapter { item ->
                viewModel.onFoodItemClick(item)
            }
            adapter = foodAdapter
            layoutManager = LinearLayoutManager(requireContext())
            viewModel
                .foodItems
                .observe(viewLifecycleOwner) {
                    foodAdapter.submitList(it)
                }
        }

        viewModel.error.observe(viewLifecycleOwner) {
            Toast.makeText(requireContext(), "Something happened $it", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

internal class FoodAdapter(
    private val onItemClick: (String) -> Unit
) : ListAdapter<
        String,
        FoodAdapter.ViewHolder
        >(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(
            inflater
                .inflate(
                    android.R.layout.simple_list_item_1,
                    parent,
                    false
                )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val textView: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(foodItem: String, onItemClick: (String) -> Unit) {
            textView.text = foodItem
            textView.setOnClickListener {
                onItemClick(foodItem)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<String>() {

        override fun areItemsTheSame(
            oldItem: String,
            newItem: String,
        ): Boolean {
            return oldItem == newItem
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(
            oldItem: String,
            newItem: String,
        ): Boolean {
            return oldItem == newItem
        }
    }

}

// Datasource
// Repository
// Feature
// - MVVM
// - UI, - Recyclerview

interface FoodDataSource {

    suspend fun search(query: String): Result<List<String>>

    suspend fun getFood(): Result<List<String>>
}

interface Cache<T> {

    suspend fun get(): T?

    suspend fun set(value: T?)

    suspend fun clear()
}

class InMemoryCache<T> : com.example.myapplication.Cache<T> {

    private var value: T? = null

    override suspend fun get(): T? {
        return value
    }

    override suspend fun set(value: T?) {
        this.value = value
    }

    override suspend fun clear() {
        this.value = null
    }
}

class FoodDataSourceImpl @Inject constructor(
    private val assetManager: AssetManager,
    private val dispatcher: CoroutineDispatcher,
    private val cache: Cache<List<String>>
) : FoodDataSource {

    override suspend fun search(query: String): Result<List<String>> {
        return withContext(dispatcher) {
            val data = cache.get()
            if (data == null) {
                Result.failure(IllegalStateException("Need to call getFood first"))
            } else {
                Result.success(data.filter { food -> food.contains(query, ignoreCase = true) })
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun getFood(): Result<List<String>> {
        return withContext(dispatcher) {
            try {
                val myInputStream = assetManager.open(TEXT_FILE)
                val size: Int = myInputStream.available()
                val buffer = ByteArray(size)
                myInputStream.read(buffer)
                myInputStream.close()

                val data = String(buffer).split(",").toList()
                cache.set(data)
                Result.success(data)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    companion object {

        private const val TEXT_FILE = "food.txt"
    }
}

interface FoodRepository {

    suspend fun search(query: String): Result<List<String>>

    suspend fun getFood(): Result<List<String>>
}

class FoodRepositoryImpl @Inject constructor(
    private val dataSource: FoodDataSource
) : FoodRepository {

    override suspend fun search(query: String): Result<List<String>> {
        return dataSource.search(query).map { list -> list.filter { it.isNotBlank() } }
    }

    override suspend fun getFood(): Result<List<String>> {
        return dataSource.getFood().map { list -> list.filter { it.isNotBlank() } }
    }
}

interface FoodListContract {

    interface ViewModel {

        val foodItems: LiveData<List<String>>

        val error: LiveData<Error>

        fun onFoodItemClick(foodItem: String)

        fun onSearch(query: String)
    }

    interface Repository {

        val foodData: Flow<Result<List<String>>>

        suspend fun onSearch(query: String): Result<List<String>>
    }

    sealed class Error {

        object FetchingData : Error()

        object Searching : Error()
    }
}

class FoodListViewModel(
    private val repository: FoodListContract.Repository
) : ViewModel(), FoodListContract.ViewModel {

    private val foodData: MutableSharedFlow<List<String>> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = 1)

    override val foodItems: MutableLiveData<List<String>> = MutableLiveData()

    override val error: MutableLiveData<FoodListContract.Error> = MutableLiveData()

    init {
        repository
            .foodData
            .onEach { result ->
                result
                    .onFailure { error.value = FoodListContract.Error.FetchingData }
                    .onSuccess { foodData.emit(it) }
            }
            .catch { error.value = FoodListContract.Error.FetchingData }
            .launchIn(viewModelScope)

        foodData
            .distinctUntilChanged()
            .onEach { foodItems.value = it }
            .catch { error.value = FoodListContract.Error.FetchingData }
            .launchIn(viewModelScope)
    }

    override fun onFoodItemClick(foodItem: String) {
    }

    override fun onSearch(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                val originalData = foodData.firstOrNull()
                if (originalData != null) {
                    foodItems.postValue(originalData)
                } else {
                    error.value = FoodListContract.Error.Searching
                }
            } else {
                repository
                    .onSearch(query)
                    .onSuccess { foodItems.value = it }
                    .onFailure { error.value = FoodListContract.Error.Searching }
            }
        }
    }
}

class FoodListRepository @Inject constructor(
    private val foodRepository: FoodRepository
) : FoodListContract.Repository {

    override val foodData: Flow<Result<List<String>>> = flow {
        emit(foodRepository.getFood())
    }

    override suspend fun onSearch(query: String): Result<List<String>> {
        return foodRepository.search(query)
    }
}

class FoodViewModelFactory @Inject constructor(
    private val repository: FoodListContract.Repository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return if (modelClass.isAssignableFrom(FoodListViewModel::class.java)) {
            FoodListViewModel(repository) as T
        } else {
            throw UnsupportedOperationException()
        }
    }
}

@InstallIn(FragmentComponent::class)
@Module
abstract class FoodListFragmentModule {

    @Binds
    abstract fun bindsViewModelFactory(
        impl: FoodViewModelFactory
    ): ViewModelProvider.Factory

    @Binds
    abstract fun bindsRepository(
        impl: FoodListRepository
    ): FoodListContract.Repository
}

@InstallIn(SingletonComponent::class)
@Module
abstract class FoodDataModule {

    @Binds
    abstract fun bindsRepository(
        impl: FoodRepositoryImpl
    ): FoodRepository

    @Binds
    abstract fun bindsDataSource(
        impl: FoodDataSourceImpl
    ): FoodDataSource

    companion object {

        @Provides
        fun provideAssetManager(
            @ApplicationContext context: Context
        ): AssetManager {
            return context.resources.assets
        }

        @Provides
        fun provideCache(): Cache<List<String>> {
            return InMemoryCache()
        }

        @Provides
        fun provideDispatcher(): CoroutineDispatcher {
            return Dispatchers.IO
        }
    }
}
