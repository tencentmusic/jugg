package com.sickworm.intellij.jugg.compiler.databinding

import java.io.File

class DataBindingTemplates(isUseAndroidX: Boolean) {

    fun generateFullMapperFile(originMapperFile: File, fullMapperFile: File) {
        val originText = originMapperFile.readText().replace("class DataBinderMapperImpl", "class DataBinderMapperImpl_Full")
        fullMapperFile.writeText(originText)
    }

    val holderTemplate = if (isUseAndroidX) {
        """
package _package_name_holder_;

import androidx.databinding.DataBinderMapper;

public class DataBinderMapper_IncrementalHolder {
    public static DataBinderMapper[] get() {
        return new DataBinderMapper[] {
            _inc_mapper_array_holder_
        };
    }
}
            """
    } else {
        """
package _package_name_holder_;

import android.databinding.DataBinderMapper;

public class DataBinderMapper_IncrementalHolder {
    public static DataBinderMapper[] get() {
        return new DataBinderMapper[] {
            _inc_mapper_array_holder_
        };
    }
}
            """
    }


    val mapperContentTemplate = if (isUseAndroidX) {
        """
package _package_name_holder_;

import androidx.databinding.DataBinderMapper;
import androidx.databinding.DataBindingComponent;
import androidx.databinding.ViewDataBinding;
import android.view.View;
import java.lang.Override;
import java.lang.String;
import java.util.List;

public class DataBinderMapperImpl extends DataBinderMapper {
    private final _package_name_holder_.DataBinderMapperImpl_Full origin = new _package_name_holder_.DataBinderMapperImpl_Full();
    private final DataBinderMapper[] incDataBinderMapperArray = DataBinderMapper_IncrementalHolder.get();

    @Override
    public ViewDataBinding getDataBinder(DataBindingComponent component, View view, int layoutId) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                ViewDataBinding viewDataBinding = inc.getDataBinder(component, view, layoutId);
                if (viewDataBinding != null) {
                    return viewDataBinding;
                }
            }
        }
        return origin.getDataBinder(component, view, layoutId);
    }

    @Override
    public ViewDataBinding getDataBinder(DataBindingComponent component, View[] views, int layoutId) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                ViewDataBinding viewDataBinding = inc.getDataBinder(component, views, layoutId);
                if (viewDataBinding != null) {
                    return viewDataBinding;
                }
            }
        }
        return origin.getDataBinder(component, views, layoutId);
    }

    @Override
    public int getLayoutId(String tag) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                int layoutId = inc.getLayoutId(tag);
                if (layoutId != 0) {
                    return layoutId;
                }
            }
        }
        return origin.getLayoutId(tag);
    }

    @Override
    public String convertBrIdToString(int localId) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                String str = inc.convertBrIdToString(localId);
                if (str != null) {
                    return str;
                }
            }
        }
        return origin.convertBrIdToString(localId);
    }

    @Override
    public List<DataBinderMapper> collectDependencies() {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                List<DataBinderMapper> list = inc.collectDependencies();
                if (list != null) {
                    return list;
                }
            }
        }
        return origin.collectDependencies();
    }
}
        """
    } else {
        """
package _package_name_holder_;

import android.databinding.DataBinderMapper;
import android.databinding.DataBindingComponent;
import android.databinding.ViewDataBinding;
import android.view.View;
import java.lang.Override;
import java.lang.String;
import java.util.List;

public class DataBinderMapperImpl extends DataBinderMapper {
    private final _package_name_holder_.DataBinderMapperImpl_Full origin = new _package_name_holder_.DataBinderMapperImpl_Full();
    private final DataBinderMapper[] incDataBinderMapperArray = DataBinderMapper_IncrementalHolder.get();

    @Override
    public ViewDataBinding getDataBinder(DataBindingComponent component, View view, int layoutId) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                ViewDataBinding viewDataBinding = inc.getDataBinder(component, view, layoutId);
                if (viewDataBinding != null) {
                    return viewDataBinding;
                }
            }
        }
        return origin.getDataBinder(component, view, layoutId);
    }

    @Override
    public ViewDataBinding getDataBinder(DataBindingComponent component, View[] views, int layoutId) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                ViewDataBinding viewDataBinding = inc.getDataBinder(component, views, layoutId);
                if (viewDataBinding != null) {
                    return viewDataBinding;
                }
            }
        }
        return origin.getDataBinder(component, views, layoutId);
    }

    @Override
    public int getLayoutId(String tag) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                int layoutId = inc.getLayoutId(tag);
                if (layoutId != 0) {
                    return layoutId;
                }
            }
        }
        return origin.getLayoutId(tag);
    }

    @Override
    public String convertBrIdToString(int localId) {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                String str = inc.convertBrIdToString(localId);
                if (str != null) {
                    return str;
                }
            }
        }
        return origin.convertBrIdToString(localId);
    }

    @Override
    public List<DataBinderMapper> collectDependencies() {
        if (incDataBinderMapperArray.length > 0) {
            for (DataBinderMapper inc: incDataBinderMapperArray) {
                List<DataBinderMapper> list = inc.collectDependencies();
                if (list != null) {
                    return list;
                }
            }
        }
        return origin.collectDependencies();
    }
}
        """
    }

}