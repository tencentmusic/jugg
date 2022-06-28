package com.sickworm.intellij.jugg.ide.toolWindow;

import javax.swing.table.DefaultTableModel;

public class FileTableModel extends DefaultTableModel {

    public FileTableModel(Object[][] data, Object[] columnNames) {
        super(data, columnNames);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }
}
