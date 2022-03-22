package io.ebeaninternal.dbmigration.ddlgeneration.platform;

import java.util.ArrayList;
import java.util.List;

import io.ebean.config.dbplatform.DatabasePlatform;
import io.ebean.util.StringHelper;
import io.ebeaninternal.dbmigration.ddlgeneration.DdlAlterTable;
import io.ebeaninternal.dbmigration.ddlgeneration.DdlBuffer;
import io.ebeaninternal.dbmigration.ddlgeneration.DdlWrite;
import io.ebeaninternal.dbmigration.migration.AlterColumn;
import io.ebeaninternal.dbmigration.migration.Column;

/**
 * DB2 platform specific DDL.
 */
public class DB2Ddl extends PlatformDdl {
  private static final String MOVE_TABLE = "CALL SYSPROC.ADMIN_MOVE_TABLE(CURRENT_SCHEMA,'%s','%s','%s','%s','','','','','','MOVE')";

  public DB2Ddl(DatabasePlatform platform) {
    super(platform);
    this.dropTableIfExists = "drop table ";
    this.dropSequenceIfExists = "drop sequence ";
    this.dropConstraintIfExists = "NOT USED";
    this.dropIndexIfExists = "NOT USED";
    this.identitySuffix = " generated by default as identity";
    this.columnSetNull = "drop not null";
    this.columnSetType = "set data type ";
    this.inlineUniqueWhenNullable = false;
    this.historyDdl = new Db2HistoryDdl();
  }

  @Override
  public String alterTableTablespace(String tablename, String tableSpace, String indexSpace, String lobSpace) {
    if(tableSpace == null) {
      // if no tableSpace set, use the default tablespace USERSPACE1
      return String.format(MOVE_TABLE, tablename.toUpperCase(), "USERSPACE1", "USERSPACE1", "USERSPACE1");
    } else {
      return String.format(MOVE_TABLE, tablename.toUpperCase(), tableSpace, indexSpace, lobSpace);
    }
  }
  
  @Override
  public String alterTableAddUniqueConstraint(String tableName, String uqName, String[] columns, String[] nullableColumns) {
    if (nullableColumns == null || nullableColumns.length == 0) {
      return super.alterTableAddUniqueConstraint(tableName, uqName, columns, nullableColumns);
    }     

    if (uqName == null) {
      throw new NullPointerException();
    }
    StringBuilder sb = new StringBuilder("create unique index ");
    sb.append(maxConstraintName(uqName)).append(" on ").append(tableName).append('(');

    for (int i = 0; i < columns.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(columns[i]);
    }
    sb.append(") exclude null keys");
    return sb.toString();
  }

  @Override
  public void addTablespace(DdlBuffer apply, String tablespaceName, String indexTablespace, String lobTablespace) {
    apply.append(" in ").append(tablespaceName).append(" index in ").append(indexTablespace).append(" long in ").append(lobTablespace);
  }
  
  @Override
  public void alterTableAddColumn(DdlWrite writer, String tableName, Column column, boolean onHistoryTable, String defaultValue) {

    String convertedType = convert(column.getType());
    DdlBuffer buffer = alterTable(writer, tableName).append(addColumn, column.getName());
    buffer.append(convertedType);

    // Add default value also to history table if it is not excluded
    if (defaultValue != null) {
      buffer.append(" default ");
      buffer.append(defaultValue);
    }

    if (isTrue(column.isNotnull())) {
      buffer.appendWithSpace(columnNotNull);
    }
    // DB2 History table must match exact!
    if (!onHistoryTable) {
      // check constraints cannot be added in one statement for h2
      if (!StringHelper.isNull(column.getCheckConstraint())) {
        String ddl = alterTableAddCheckConstraint(tableName, column.getCheckConstraintName(), column.getCheckConstraint());
        writer.applyPostAlter().appendStatement(ddl);
      }
    }

  }
  @Override
  public String alterTableDropForeignKey(String tableName, String fkName) {
    return alterTableDropConstraint(tableName, fkName);
  };

  @Override
  public String alterTableDropUniqueConstraint(String tableName, String uniqueConstraintName) {
    return alterTableDropConstraint(tableName, uniqueConstraintName)
      + "\n" + dropIndex(uniqueConstraintName, tableName);
  }

  @Override
  public String alterTableDropConstraint(String tableName, String constraintName) {
    StringBuilder sb = new StringBuilder(300);
    sb.append("delimiter $$\n")
      .append("begin\n")
      .append("if exists (select constname from syscat.tabconst where tabschema = current_schema and constname = '")
      .append(maxConstraintName(constraintName).toUpperCase())

      .append("' and tabname = '").append(naming.normaliseTable(tableName).toUpperCase()).append("') then\n")

      .append("  prepare stmt from 'alter table ").append(tableName)
      .append(" drop constraint ").append(maxConstraintName(constraintName)).append("';\n")

      .append("  execute stmt;\n")
      .append("end if;\n")
      .append("end$$");
    return sb.toString();

  }

  @Override
  public String dropIndex(String indexName, String tableName, boolean concurrent) {
    StringBuilder sb = new StringBuilder(300);
    sb.append("delimiter $$\n")
      .append("begin\n")
      .append("if exists (select indname from syscat.indexes where indschema = current_schema and indname = '")
      .append(maxConstraintName(indexName).toUpperCase()).append("') then\n")
      .append("  prepare stmt from 'drop index ").append(maxConstraintName(indexName)).append("';\n")
      .append("  execute stmt;\n")
      .append("end if;\n")
      .append("end$$");
    return sb.toString();
  }

  @Override
  public String dropSequence(String sequenceName) {
    StringBuilder sb = new StringBuilder(300);
    sb.append("delimiter $$\n");
    sb.append("begin\n");
    sb.append("if exists (select seqschema from syscat.sequences where seqschema = current_schema and seqname = '")
      .append(maxConstraintName(sequenceName).toUpperCase()).append("') then\n");
    sb.append("  prepare stmt from 'drop sequence ").append(maxConstraintName(sequenceName)).append("';\n");
    sb.append("  execute stmt;\n");
    sb.append("end if;\n");
    sb.append("end$$");
    return sb.toString();
  }

  @Override
  protected void alterColumnType(DdlWrite writer, AlterColumn alter) {
    String type = convert(alter.getType());
    DB2ColumnOptionsParser parser = new DB2ColumnOptionsParser(type);
    alterTable(writer, alter.getTableName()).append(alterColumn, alter.getColumnName())
      .append(columnSetType).append(parser.getType());

    if (parser.getInlineLength() != null) {
      alterTable(writer, alter.getTableName()).append(alterColumn, alter.getColumnName())
        .append("set").appendWithSpace(parser.getInlineLength());
    }

    if (parser.hasExtraOptions()) {
      alterTable(writer, alter.getTableName()).raw("-- ignored options for ")
        .append(alter.getTableName()).append(".").append(alter.getColumnName())
        .append(": compact=").append(String.valueOf(parser.isCompact()))
        .append(", logged=").append(String.valueOf(parser.isLogged()));
    }
  }

  @Override
  protected DdlAlterTable alterTable(DdlWrite writer, String tableName) {
    return writer.applyAlterTable(tableName, Db2AlterTableWrite::new);
  };

  class Db2AlterTableWrite extends BaseAlterTableWrite {

    public Db2AlterTableWrite(String tableName) {
      super(tableName, DB2Ddl.this);
    }

    @Override
    protected List<AlterCmd> postProcessCommands(List<AlterCmd> cmds) {
      List<AlterCmd> ret = new ArrayList<>(cmds.size() + 1);
      boolean requiresReorg = false;
      for (AlterCmd cmd : cmds) {
        ret.add(cmd);
        if (!requiresReorg && checkReorg(cmd)) {
          requiresReorg = true;
        }
      }
      if (requiresReorg) {
        ret.add(newRawCommand("call sysproc.admin_cmd('reorg table " + tableName() + "')"));
      }
      return ret;
    }

    /**
     * determine, if we need a reorg.
     * 
     * See: https://www.ibm.com/docs/en/db2/11.5?topic=statements-alter-table The following is the full list of REORG-recommended
     * ALTER statements that cause a version change and place the table into a REORG-pending state:
     * <ul>
     * <li>DROP COLUMN
     * <li>ALTER COLUMN SET NOT NULL
     * <li>ALTER COLUMN DROP NOT NULL
     * <li>ALTER COLUMN SET DATA TYPE, except in the following situations:<br>
     * Increasing the length of a VARCHAR or VARGRAPHIC column<br>
     * Decreasing the length of a VARCHAR or VARGRAPHIC column without truncating trailing blanks from existing data, when no indexes
     * exist on the column
     * </ul>
     * 
     */
    private boolean checkReorg(AlterCmd cmd) {
      switch (cmd.getOperation()) {
      case "drop column":
        return true;
      case "alter column":
        String alter = cmd.getAlternation();
        return alter.equals("set not null")
          || alter.equals("drop not default")
          || alter.startsWith("set data type"); // note: altering varchar length only is not detected here
      default:
        return false;
      }
    }
  }
}
