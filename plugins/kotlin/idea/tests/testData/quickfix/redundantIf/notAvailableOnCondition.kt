// "Remove redundant 'if' statement" "false"
// ACTION: Expand boolean expression to 'if else'
// ACTION: Remove braces from all 'if' statements
// IGNORE_FIR
fun bar(value: Int): Boolean {
    if (<caret>value % 2 == 0) {
        return true
    } else {
        return false
    }
}