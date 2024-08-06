<script>
	import { ParaglideJS } from '@inlang/paraglide-sveltekit'
	import { i18n } from '$lib/i18n'

    import '../app.scss'
</script>
<ParaglideJS {i18n}>


<main class="container">
    <slot />
</main>
</ParaglideJS>