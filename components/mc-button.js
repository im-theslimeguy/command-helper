const TEMPLATE = `
  <a class="frame-21" part="frame">
    <span class="label" part="label"></span>
  </a>
`;

export class McButton extends HTMLElement {
  static get observedAttributes() {
    return ["label", "href", "target", "rel", "disabled"];
  }

  constructor() {
    super();
    this.handlePointerDown = this.handlePointerDown.bind(this);
    this.handlePointerUp = this.handlePointerUp.bind(this);
    this.handlePointerLeave = this.handlePointerLeave.bind(this);
    this.handleKeyDown = this.handleKeyDown.bind(this);
    this.handleKeyUp = this.handleKeyUp.bind(this);
    this.handleClick = this.handleClick.bind(this);
  }

  connectedCallback() {
    if (!this.dataset.initialized) {
      this.innerHTML = TEMPLATE;
      this.dataset.initialized = "true";
    }

    this.control = this.querySelector(".frame-21");
    this.labelNode = this.querySelector(".label");

    this.syncFromAttributes();
    this.bindEvents();
  }

  disconnectedCallback() {
    this.unbindEvents();
  }

  attributeChangedCallback() {
    if (!this.isConnected) {
      return;
    }

    this.syncFromAttributes();
  }

  bindEvents() {
    if (!this.control || this.dataset.bound === "true") {
      return;
    }

    this.control.addEventListener("pointerdown", this.handlePointerDown);
    this.control.addEventListener("pointerup", this.handlePointerUp);
    this.control.addEventListener("pointerleave", this.handlePointerLeave);
    this.control.addEventListener("pointercancel", this.handlePointerLeave);
    this.control.addEventListener("keydown", this.handleKeyDown);
    this.control.addEventListener("keyup", this.handleKeyUp);
    this.control.addEventListener("click", this.handleClick);
    this.dataset.bound = "true";
  }

  unbindEvents() {
    if (!this.control || this.dataset.bound !== "true") {
      return;
    }

    this.control.removeEventListener("pointerdown", this.handlePointerDown);
    this.control.removeEventListener("pointerup", this.handlePointerUp);
    this.control.removeEventListener("pointerleave", this.handlePointerLeave);
    this.control.removeEventListener("pointercancel", this.handlePointerLeave);
    this.control.removeEventListener("keydown", this.handleKeyDown);
    this.control.removeEventListener("keyup", this.handleKeyUp);
    this.control.removeEventListener("click", this.handleClick);
    delete this.dataset.bound;
  }

  syncFromAttributes() {
    const label = this.getAttribute("label") || this.textContent.trim() || "Button";
    const href = this.getAttribute("href") || "#";
    const target = this.getAttribute("target");
    const rel = this.getAttribute("rel");
    const disabled = this.hasAttribute("disabled");

    this.labelNode.textContent = label;
    this.control.setAttribute("href", disabled ? "#" : href);
    this.control.setAttribute("aria-label", label);
    this.control.setAttribute("tabindex", disabled ? "-1" : "0");
    this.control.setAttribute("aria-disabled", disabled ? "true" : "false");

    if (target) {
      this.control.setAttribute("target", target);
    } else {
      this.control.removeAttribute("target");
    }

    if (rel) {
      this.control.setAttribute("rel", rel);
    } else {
      this.control.removeAttribute("rel");
    }

    this.toggleAttribute("data-disabled", disabled);
  }

  handlePointerDown(event) {
    if (event.button !== 0 || this.hasAttribute("disabled")) {
      return;
    }

    this.dataset.pressed = "true";
  }

  handlePointerUp() {
    delete this.dataset.pressed;
  }

  handlePointerLeave() {
    delete this.dataset.pressed;
  }

  handleKeyDown(event) {
    if (this.hasAttribute("disabled")) {
      return;
    }

    if (event.key === " " || event.key === "Enter") {
      this.dataset.pressed = "true";
    }
  }

  handleKeyUp(event) {
    if (event.key === " " || event.key === "Enter") {
      delete this.dataset.pressed;
    }
  }

  handleClick(event) {
    if (!this.hasAttribute("disabled")) {
      return;
    }

    event.preventDefault();
  }
}

export function registerMcButton() {
  if (!customElements.get("mc-button")) {
    customElements.define("mc-button", McButton);
  }
}
