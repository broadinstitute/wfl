(ns zero.jdbc
  "clojure.tools.logging wrapping for clojure.java.jdbc"
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging.readable :as log]))

(defmacro query
  "Logged alias for [[clojure.java.jdbc/query]]:

  Given a database connection and a vector containing SQL and optional parameters,
  perform a simple database query. The options specify how to construct the result
  set (and are also passed to prepare-statement as needed):
    :as-arrays? - return the results as a set of arrays, default false.
    :identifiers - applied to each column name in the result set, default lower-case
    :keywordize? - defaults to true, can be false to opt-out of converting
        identifiers to keywords
    :qualifier - optionally provides the namespace qualifier for identifiers
    :result-set-fn - applied to the entire result set, default doall / vec
        if :as-arrays? true, :result-set-fn will default to vec
        if :as-arrays? false, :result-set-fn will default to doall
    :row-fn - applied to each row as the result set is constructed, default identity
  The second argument is a vector containing a SQL string or PreparedStatement, followed
  by any parameters it needs.
  See also prepare-statement for additional options."
  ([db sql-params] `(do
                      (log/info "JDBC SQL query (without opts):" ~db ~sql-params)
                      (jdbc/query ~db ~sql-params)))
  ([db sql-params opts] `(do
                          (log/info "JDBC SQL query:" ~db ~sql-params ~opts)
                          (jdbc/query ~db ~sql-params ~opts))))

(defmacro update!
  "Logged alias for [[clojure.java.jdbc/update!]]:

  Given a database connection, a table name, a map of column values to set and a
  where clause of columns to match, perform an update. The options may specify
  how column names (in the set / match maps) should be transformed (default
                                                                     'as-is') and whether to run the update in a transaction (default true).
  Example:
  (update! db :person {:zip 94540} [\"zip = ?\" 94546])
  is equivalent to:
  (execute! db [\"UPDATE person SET zip = ? WHERE zip = ?\" 94540 94546])"
  ([db table set-map where-clause] `(do
                                      (log/info "JDBC SQL update! (without opts):" ~db ~table ~set-map ~where-clause)
                                      (jdbc/update! ~db ~table ~set-map ~where-clause)))
  ([db table set-map where-clause opts] `(do
                                      (log/info "JDBC SQL update!:" ~db ~table ~set-map ~where-clause ~opts)
                                      (jdbc/update! ~db ~table ~set-map ~where-clause ~opts))))

(defmacro insert-multi!
  "Logged alias for [[clojure.java.jdbc/insert-multi!]]:

  Given a database connection, a table name and either a sequence of maps (for
  rows) or a sequence of column names, followed by a sequence of vectors (for
  the values in each row), and possibly a map of options, insert that data into
  the database.

  When inserting rows as a sequence of maps, the result is a sequence of the
  generated keys, if available (note: PostgreSQL returns the whole rows). A
  separate database operation is used for each row inserted. This may be slow
  for if a large sequence of maps is provided.

  When inserting rows as a sequence of lists of column values, the result is
  a sequence of the counts of rows affected (a sequence of 1's), if available.
  Yes, that is singularly unhelpful. Thank you getUpdateCount and executeBatch!
  A single database operation is used to insert all the rows at once. This may
  be much faster than inserting a sequence of rows (which performs an insert for
  each map in the sequence).

  The :transaction? option specifies whether to run in a transaction or not.
  The default is true (use a transaction). The :entities option specifies how
  to convert the table name and column names to SQL entities."
  ([db table rows] `(do
                      (log/info "JDBC SQL insert-rows! (without opts):" ~db ~table ~rows)
                      (jdbc/insert-multi! ~db ~table ~rows)))
  ([db table cols-or-rows values-or-opts] `(do
                                             (if (map? values-or-opts)
                                               (log/info "JDBC SQL insert-rows!:" ~db ~table ~cols-or-rows ~values-or-opts)
                                               (log/info "JDBC SQL insert-cols! (without opts):" ~db ~table ~cols-or-rows ~values-or-opts))
                                             (jdbc/insert-multi! ~db ~table ~cols-or-rows ~values-or-opts)))
  ([db table cols values opts] `(do
                                  (log/info "JDBC SQL insert-cols!:" ~db ~table ~cols ~values ~opts)
                                  (jdbc/insert-multi! ~db ~table ~cols ~values ~opts))))

(defmacro execute!
  "Logged alias for [[clojure.java.jdbc/execute!]]:

  Given a database connection and a vector containing SQL (or PreparedStatement)
  followed by optional parameters, perform a general (non-select) SQL operation.

  The :transaction? option specifies whether to run the operation in a
  transaction or not (default true).

  If the :multi? option is false (the default), the SQL statement should be
  followed by the parameters for that statement.

  If the :multi? option is true, the SQL statement should be followed by one or
  more vectors of parameters, one for each application of the SQL statement.

  If :return-keys is provided, db-do-prepared-return-keys will be called
  instead of db-do-prepared, and the result will be a sequence of maps
  containing the generated keys. If present, :row-fn will be applied. If :multi?
  then :result-set-fn will also be applied if present. :as-arrays? may also be
  specified (which will affect what :result-set-fn is passed).

  If there are no parameters specified, executeUpdate will be used, otherwise
  executeBatch will be used. This may affect what SQL you can run via execute!"
  ([db sql-params] `(do
                      (log/info "JDBC SQL execute! (without opts):" ~db ~sql-params)
                      (jdbc/execute! ~db ~sql-params)))
  ([db sql-params opts] `(do
                           (log/info "JDBC SQL execute!:" ~db ~sql-params ~opts)
                           (jdbc/execute! ~db ~sql-params ~opts))))

(defmacro db-do-commands
  "Logged alias for [[clojure.java.jdbc/db-do-commands]]:

  Executes SQL commands on the specified database connection. Wraps the commands
  in a transaction if transaction? is true. transaction? can be omitted and it
  defaults to true. Accepts a single SQL command (string) or a vector of them.
  Uses executeBatch. This may affect what SQL you can run via db-do-commands."
  ([db sql-commands] `(do
                        (log/info "JDBC SQL db-do-commands:" ~db ~sql-commands)
                        (jdbc/db-do-commands ~db ~sql-commands)))
  ([db transaction? sql-commands] `(do
                                     (log/info "JDBC SQL db-do-commands:" ~db ~transaction? ~sql-commands)
                                     (jdbc/db-do-commands ~db ~transaction? ~sql-commands))))

(defmacro insert!
  "Logged alias for [[clojure.java.jdbc/insert!]]:

  Given a database connection, a table name and either a map representing a rows,
  or a list of column names followed by a list of column values also representing
  a single row, perform an insert.
  When inserting a row as a map, the result is the database-specific form of the
  generated keys, if available (note: PostgreSQL returns the whole row).
  When inserting a row as a list of column values, the result is the count of
  rows affected (1), if available (from getUpdateCount after executeBatch).
  The row map or column value vector may be followed by a map of options:
  The :transaction? option specifies whether to run in a transaction or not.
  The default is true (use a transaction). The :entities option specifies how
  to convert the table name and column names to SQL entities."
  ([db table row] `(do
                      (log/info "JDBC SQL insert-rows! (without opts):" ~db ~table [~row])
                      (jdbc/insert! ~db ~table ~row)))
  ([db table cols-or-row values-or-opts] `(do
                                             (if (map? values-or-opts)
                                               (log/info "JDBC SQL insert-rows!:" ~db ~table [~cols-or-row] ~values-or-opts)
                                               (log/info "JDBC SQL insert-cols! (without opts):" ~db ~table ~cols-or-row [~values-or-opts]))
                                             (jdbc/insert! ~db ~table ~cols-or-row ~values-or-opts)))
  ([db table cols values opts] `(do
                                  (log/info "JDBC SQL insert-cols!:" ~db ~table ~cols [~values] ~opts)
                                  (jdbc/insert! ~db ~table ~cols ~values ~opts))))

(defmacro with-db-transaction
  "Logged alias for [[clojure.java.jdbc/with-db-transaction]]:

  Evaluates body in the context of a transaction on the specified database connection.
  The binding provides the database connection for the transaction and the name to which
  that is bound for evaluation of the body. The binding may also specify the isolation
  level for the transaction, via the :isolation option and/or set the transaction to
  readonly via the :read-only? option.
  (with-db-transaction [t-con db-spec {:isolation level :read-only? true}]
    ... t-con ...)
  See db-transaction* for more details.

  Logging note: for readability a weakly random number is printed with both the start and
  of the transaction to help disambiguate between multiple concurrent transactions."
  [binding & body]
  `(let [id# (rand-int 10000)]
     (log/info "JDBC SQL transaction to" ~(second binding) "(Look for" id# "ending below)")
     (jdbc/with-db-transaction ~binding ~@body)
     (log/info "JDBC SQL transaction ended (Look for" id# "being initiated above)")))

(defmacro prepare-statement
  "Alias for [[clojure.java.jdbc/prepare-statement]], does not log since the statement would be used later:

  Create a prepared statement from a connection, a SQL string and a map
  of options:
     :return-keys truthy | nil - default nil
       for some drivers, this may be a vector of column names to identify
       the generated keys to return, otherwise it should just be true
     :result-type :forward-only | :scroll-insensitive | :scroll-sensitive
     :concurrency :read-only | :updatable
     :cursors     :hold | :close
     :fetch-size  n
     :max-rows    n
     :timeout     n
  Note that :result-type and :concurrency must be specified together as the
  underlying Java API expects both (or neither)."
  ([con sql] `(jdbc/prepare-statement ~con ~sql))
  ([^java.sql.Connection con ^String sql opts] `(jdbc/prepare-statement ~con ~sql ~opts)))

(defmacro get-connection
  "Logged alias for [[clojure.java.jdbc/prepare-statement]]:

  Creates a connection to a database. db-spec is usually a map containing connection
  parameters but can also be a URI or a String.

  The only time you should call this function is when you need a Connection for
  prepare-statement -- no other public functions in clojure.java.jdbc accept a
  raw Connection object: they all expect a db-spec (either a raw db-spec or one
  obtained via with-db-connection or with-db-transaction).

  The correct usage of get-connection for prepare-statement is:

      (with-open [conn (jdbc/get-connection db-spec)]
        ... (jdbc/prepare-statement conn sql-statement options) ...)

  Any connection obtained via calling get-connection directly must be closed
  explicitly (via with-open or a direct call to .close on the Connection object).

  The various possibilities are described below:

  DriverManager (preferred):
    :dbtype      (required) a String, the type of the database (the jdbc subprotocol)
    :dbname      (required) a String, the name of the database
    :classname   (optional) a String, the jdbc driver class name
    :host        (optional) a String, the host name/IP of the database
                            (defaults to 127.0.0.1)
    :port        (optional) a Long, the port of the database
                            (defaults to 3306 for mysql, 1433 for mssql/jtds, else nil)
    (others)     (optional) passed to the driver as properties
                            (may include :user and :password)

  Raw:
    :connection-uri (required) a String
                 Passed directly to DriverManager/getConnection
                 (both :user and :password may be specified as well, rather
                  than passing them as part of the connection string)

  Other formats accepted:

  Existing Connection:
    :connection  (required) an existing open connection that can be used
                 but cannot be closed (only the parent connection can be closed)

  DriverManager (alternative / legacy style):
    :subprotocol (required) a String, the jdbc subprotocol
    :subname     (required) a String, the jdbc subname
    :classname   (optional) a String, the jdbc driver class name
    (others)     (optional) passed to the driver as properties
                            (may include :user and :password)

  Factory:
    :factory     (required) a function of one argument, a map of params
    (others)     (optional) passed to the factory function in a map

  DataSource:
    :datasource  (required) a javax.sql.DataSource
    :username    (optional) a String - deprecated, use :user instead
    :user        (optional) a String - preferred
    :password    (optional) a String, required if :user is supplied

  JNDI:
    :name        (required) a String or javax.naming.Name
    :environment (optional) a java.util.Map

  java.net.URI:
    Parsed JDBC connection string (see java.lang.String format next)

  java.lang.String:
    subprotocol://user:password@host:post/subname
                 An optional prefix of jdbc: is allowed."
  ([db-spec] `(do
               (log/info "JBDC SQL connection made (no opts):" ~db-spec)
               (jdbc/get-connection ~db-spec)))
  ([db-spec opts] `(do
                     (log/info "JBDC SQL connection made:" ~db-spec ~opts)
                     (jdbc/get-connection ~db-spec ~opts))))

