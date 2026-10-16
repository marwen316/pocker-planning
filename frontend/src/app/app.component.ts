import { Component } from "@angular/core";
import { PlanningRoomComponent } from "./features/planning-room/planning-room.component";
@Component({
  selector: "app-root",
  standalone: true,
  imports: [PlanningRoomComponent],
  template: "<app-planning-room />",
})
export class AppComponent {}
