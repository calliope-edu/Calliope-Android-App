package cc.calliope.mini;

enum class LetterMapping(val number: Char) {
    Z('1'), U('1'),
    V('2'), O('2'),
    G('3'), I('3'),
    P('4'), E('4'),
    T('5'), A('5');

    companion object {
        fun getNumber(letter: Char): Char {
            return values().firstOrNull { it.name[0] == letter.uppercaseChar() }?.number ?: letter
        }
    }
}