(ns songpark.jam.platform.protocol)

(defprotocol IJamDB
  "Interface Database Key Value"
  (read-db [database key-path] "Read the key path from the database")
  (write-db [database key-path value] "Write the value to the key path in the database")
  (delete-db [database key-path] "Delete at the key path"))
