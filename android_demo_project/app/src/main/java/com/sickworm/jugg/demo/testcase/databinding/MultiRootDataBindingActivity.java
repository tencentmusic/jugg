package com.sickworm.jugg.demo.testcase.databinding;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.myapplication.databinding.ActivityDataBindingMultiRootBinding;

/** Inflates a DataBinding item whose ViewHolder only configures the nested ImageView. */
public class MultiRootDataBindingActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        RecyclerView recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(new ItemAdapter());
        setContentView(recycler);
    }

    private static class ItemAdapter extends RecyclerView.Adapter<ItemHolder> {
        @Override
        public ItemHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ItemHolder(ActivityDataBindingMultiRootBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(ItemHolder holder, int position) {
            holder.binding.previewImage.setImageResource(android.R.drawable.ic_menu_gallery);
            Log.i("MultiRootDataBinding", "item bound: ImageView configured");
        }

        @Override
        public int getItemCount() {
            return 1;
        }
    }

    private static class ItemHolder extends RecyclerView.ViewHolder {
        final ActivityDataBindingMultiRootBinding binding;

        ItemHolder(ActivityDataBindingMultiRootBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
