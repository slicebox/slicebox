(function () {
   'use strict';
}());

angular.module('slicebox.adminBoxes', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/boxes', {
    templateUrl: '/assets/partials/adminBoxes.html',
    controller: 'AdminBoxesCtrl'
  });
})

.controller('AdminBoxesCtrl', function($scope, $http, $interval, $mdDialog, sbxToast, openDeleteEntitiesModalFunction, openMessageModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/boxes/', 'box(es)')
            },
            {
                name: 'Show Secret',
                action: showSecretModal,
                requiredSelectionCount: 1
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.boxesTable)) {
            $scope.callbacks.boxesTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes');
    };

    $scope.addBoxButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/addBoxModalContent.html',
            controller: 'AddBoxModalCtrl',
            scope: $scope.$new()
        });
        dialogPromise.then(function (response) {
            sbxToast.showInfoMessage("Box added");                
            $scope.callbacks.boxesTable.reloadPage();
        });
    };

    function showSecretModal(boxes) {
        if (boxes.length === 1) {
            var box = boxes[0];
            $http.get('/api/boxes/' + box.id + '/transferdata').success(function (transferData) {
                openMessageModal("Box Secret", "The secret for box <i>" + box.name + "</i> is <strong>" + transferData.secret + "</strong>");
            }).error(function (reason) {
                openMessageModal("Box Secret", "Box <i>" + box.name + "</i> does not encrypt transfers. No secret available.");                
            });
        }
    }

})

.controller('AddBoxModalCtrl', function($scope, $mdDialog, $http, sbxToast) {

    $scope.uiState = {
        addChoice: '',
        remoteBoxName: "",
        connectionURL: ""
    };

    // Scope functions
    $scope.radioButtonChanged = function() {
        $scope.addBoxForm.$setPristine();
    };

    $scope.generateURLButtonClicked = function() {
        if ($scope.addBoxForm.$invalid) {
            return;
        }

        var connectionData = {
            name: $scope.uiState.remoteBoxName
        };

        var generateURLPromise = $http.post('/api/boxes/createconnection', connectionData);

        generateURLPromise.success(function(box) {
            showBaseURLDialog(box);
            $mdDialog.hide();
        });

        generateURLPromise.error(function(reason) {
            sbxToast.showErrorMessage(reason);                
        });

        return generateURLPromise;
    };

    $scope.connectButtonClicked = function() {
        if ($scope.addBoxForm.$invalid) {
            return;
        }

        $scope.uiState.errorMessage = null;

        var connectPromise = $http.post('/api/boxes/connect',
            {
                name: $scope.uiState.remoteBoxName,
                baseUrl: $scope.uiState.connectionURL,
                secret: $scope.uiState.secret
            });

        connectPromise.success(function(box) {
            $mdDialog.hide();
        });

        connectPromise.error(function(reason) {
            sbxToast.showErrorMessage(reason);
        });

        return connectPromise;
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    // Private functions
    function showBaseURLDialog(box) {
        var transferDataPromise = $http.get("/api/boxes/" + box.id + '/transferdata');

        $mdDialog.show({
                templateUrl: '/assets/partials/baseURLModalContent.html',
                controller: 'BaseURLModalCtrl',
                locals: {
                    box: box,
                    transferData: transferDataPromise
                }
            });
    }
})

.controller('BaseURLModalCtrl', function($scope, $mdDialog, box, transferData) {
    // Initialization
    $scope.name = box.name;
    $scope.baseURL = box.baseUrl;
    $scope.secret = transferData.data.secret;

    // Scope functions
    $scope.mailBody = function() {
        var bodyText = 'Box connection URL:\n\n' + $scope.baseURL + '\n\nSecret:\n\n' + $scope.secret;

        return encodeURIComponent(bodyText);
    };

    $scope.closeButtonClicked = function() {
        $mdDialog.cancel();
    };
});